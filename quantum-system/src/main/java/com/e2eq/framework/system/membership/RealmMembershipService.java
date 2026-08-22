package com.e2eq.framework.system.membership;

import com.e2eq.framework.model.persistent.morphia.RealmTenantMembershipRepo;
import com.e2eq.framework.system.config.QuantumModeConfig;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.system.remote.RemoteMembershipClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * Control-plane membership resolution (B4 identity model; realm-membership
 * ADR: realm = placement + lifecycle, DataDomain = visibility, orgs/accounts
 * are MEMBERS of realms with exactly one owner).
 *
 * Read side of the routing registry: the IdP consults {@link #rolesForUser}
 * at token issuance to mint per-realm role claims; the tenant plane consults
 * memberships to route a principal to a realm datastore. Embedded mode reads
 * the system realm directly; remote mode (Phase C) gets this same interface
 * backed by the control-plane HTTP client.
 */
@ApplicationScoped
public class RealmMembershipService {

    @Inject
    RealmTenantMembershipRepo membershipRepo;

    @Inject
    UserRealmRoleRepo userRealmRoleRepo;

    @Inject
    QuantumModeConfig quantumModeConfig;

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    JsonWebToken jwt;

    @ConfigProperty(name = "quantum.system-service.token")
    java.util.Optional<String> serviceToken;

    /**
     * Phase C: in remote mode membership resolution goes to the control plane.
     * Capture the bearer while still on the inbound request thread. The REST
     * client may execute filters on another thread where the request-scoped JWT
     * proxy is no longer available.
     */
    private RemoteMembershipClient remote() {
        return new RemoteMembershipClient(
            quantumModeConfig.systemServiceBaseUrl().orElseThrow(() ->
                new IllegalStateException("quantum.system-service.base-url is required for remote membership resolution")),
            requestBearerToken());
    }

    Optional<String> requestBearerToken() {
        try {
            String inboundToken = jwt.getRawToken();
            if (inboundToken != null && !inboundToken.isBlank()) {
                return Optional.of(inboundToken);
            }
        } catch (RuntimeException ignored) {
            // No active request context (for example, startup). Use a configured
            // service token when present; otherwise the remote call fails closed.
        }
        return serviceToken.filter(token -> !token.isBlank());
    }

    /** All realm memberships for an org (owner and participant alike). */
    public List<RealmTenantMembership> membershipsForOrg(String orgRefName) {
        if (quantumModeConfig.isRemote()) {
            throw new IllegalStateException(
                "membershipsForOrg is not yet exposed by the control-plane API "
                + "(contracts/control-plane.openapi.yaml) — extend the contract before using "
                + "this query from a tier-2 deployment; failing loud, no local fallback.");
        }
        return membershipRepo.findByOrganizationRefNameWithIgnoreRules(
            systemDirectory.systemRealmId(), orgRefName);
    }

    /** Every org/account participating in a realm (the collaboration roster). */
    public List<RealmTenantMembership> membersOfRealm(String realmRefName) {
        if (quantumModeConfig.isRemote()) {
            return remote().membersOfRealm(realmRefName);
        }
        return membershipRepo.findByRealmRefNameWithIgnoreRules(
            systemDirectory.systemRealmId(), realmRefName);
    }

    /** Create or update an org/account membership through the owning control plane. */
    public RealmTenantMembership upsertMembership(RealmTenantMembership membership) {
        if (membership == null) {
            throw new IllegalArgumentException("membership must not be null");
        }
        if (membership.getRealmRefName() == null || membership.getRealmRefName().isBlank()) {
            throw new IllegalArgumentException("membership.realmRefName must not be blank");
        }
        if (quantumModeConfig.isRemote()) {
            throw new IllegalStateException(
                "Realm membership writes are not exposed by the generated control-plane contract; "
                    + "provision through an embedded system-management plane.");
        }
        String systemRealmId = systemDirectory.systemRealmId();
        Optional<RealmTenantMembership> existingMembership = membershipRepo.findByRealmRefNameWithIgnoreRules(
                systemRealmId, membership.getRealmRefName()).stream()
            .filter(existing -> java.util.Objects.equals(existing.getRefName(), membership.getRefName())
                || (java.util.Objects.equals(existing.getOrganizationRefName(), membership.getOrganizationRefName())
                    && java.util.Objects.equals(existing.getAccountId(), membership.getAccountId())
                    && java.util.Objects.equals(existing.getTenantId(), membership.getTenantId())))
            .findFirst();
        if (existingMembership.isPresent()) {
            RealmTenantMembership existing = existingMembership.get();
            existing.setRefName(membership.getRefName());
            existing.setRealmRefName(membership.getRealmRefName());
            existing.setRealmDisplayName(membership.getRealmDisplayName());
            existing.setOrganizationRefName(membership.getOrganizationRefName());
            existing.setAccountId(membership.getAccountId());
            existing.setTenantId(membership.getTenantId());
            existing.setRealmEmailDomain(membership.getRealmEmailDomain());
            existing.setDefaultAdminUserId(membership.getDefaultAdminUserId());
            existing.setRealmEditionRefName(membership.getRealmEditionRefName());
            existing.setProvisioningMode(membership.getProvisioningMode());
            existing.setMembershipRole(membership.getMembershipRole());
            existing.setParticipationStatus(membership.getParticipationStatus());
            existing.setSetupStatus(membership.getSetupStatus());
            existing.setSetupCompletionPercent(membership.getSetupCompletionPercent());
            existing.setDisplayName(membership.getDisplayName());
            existing.setDataDomain(membership.getDataDomain());
            return membershipRepo.save(systemRealmId, existing);
        }
        membership.setId(stableObjectId("realm-membership", membership.getRealmRefName(),
            membership.getRefName()));
        return membershipRepo.save(systemRealmId, membership);
    }

    /** The single owner membership of a realm (lifecycle/billing authority). */
    public Optional<RealmTenantMembership> ownerOfRealm(String realmRefName) {
        if (quantumModeConfig.isRemote()) {
            return remote().membersOfRealm(realmRefName).stream()
                .filter(m -> RealmTenantMembership.MEMBERSHIP_ROLE_OWNER.equals(m.getMembershipRole()))
                .findFirst();
        }
        return membershipRepo.findByRealmRefNameWithIgnoreRules(
                systemDirectory.systemRealmId(), realmRefName)
            .stream()
            .filter(membership -> RealmTenantMembership.MEMBERSHIP_ROLE_OWNER.equals(
                membership.getMembershipRole()))
            .findFirst();
    }

    /** A user's per-realm role assignments (the GitHub-model membership list). */
    public List<UserRealmRole> realmsForUser(String userId) {
        if (quantumModeConfig.isRemote()) {
            return remote().realmsForUser(userId);
        }
        return userRealmRoleRepo.findByUserIdWithIgnoreRules(
            userId, systemDirectory.systemRealmId());
    }

    /** Create or update a user's role assignment through the owning control plane. */
    public UserRealmRole upsertUserRealmRole(UserRealmRole assignment) {
        if (assignment == null) {
            throw new IllegalArgumentException("assignment must not be null");
        }
        if (assignment.getUserId() == null || assignment.getUserId().isBlank()) {
            throw new IllegalArgumentException("assignment.userId must not be blank");
        }
        if (assignment.getRealmRefName() == null || assignment.getRealmRefName().isBlank()) {
            throw new IllegalArgumentException("assignment.realmRefName must not be blank");
        }
        if (quantumModeConfig.isRemote()) {
            throw new IllegalStateException(
                "User realm-role writes are not exposed by the generated control-plane contract; "
                    + "provision through an embedded system-management plane.");
        }
        String systemRealmId = systemDirectory.systemRealmId();
        Optional<UserRealmRole> existingRole = userRealmRoleRepo.findAssignmentForRealmWithIgnoreRules(
                assignment.getUserId(), assignment.getRealmRefName(), systemRealmId);
        if (existingRole.isPresent()) {
            UserRealmRole existing = existingRole.get();
            if (assignment.getDataDomain() == null) {
                // The public control-plane membership contract intentionally omits
                // internal tenant storage scope. An update through that seam must
                // preserve the authoritative assignment instead of being treated as
                // an attempt to clear or move it.
                assignment.setDataDomain(existing.getDataDomain());
            }
            // DataDomain is the user's default within the realm. Explicit
            // multi-tenant grants live in authorizedTenantIds and must not
            // rewrite that default when another tenant is added.
            if (!java.util.Objects.equals(existing.getDataDomain(), assignment.getDataDomain())) {
                assignment.setDataDomain(existing.getDataDomain());
            }
            existing.setRefName(assignment.getRefName());
            existing.setUserId(assignment.getUserId());
            existing.setSubject(assignment.getSubject());
            existing.setRealmRefName(assignment.getRealmRefName());
            existing.setRoles(assignment.getRoles());
            existing.setAuthorizedApplications(assignment.getAuthorizedApplications());
            existing.setDefaultApplication(assignment.getDefaultApplication());
            existing.setAuthorizedTenantIds(assignment.getAuthorizedTenantIds());
            existing.setAuthorizedTenantRegEx(assignment.getAuthorizedTenantRegEx());
            existing.setSponsoringOrgRefName(assignment.getSponsoringOrgRefName());
            existing.setStatus(assignment.getStatus());
            existing.setDisplayName(assignment.getDisplayName());
            return userRealmRoleRepo.save(systemRealmId, existing);
        }
        assignment.setId(stableObjectId("user-realm-role", assignment.getUserId(),
            assignment.getRealmRefName()));
        return userRealmRoleRepo.save(systemRealmId, assignment);
    }

    /** The user's roles within one realm; empty when not a member. */
    public List<String> rolesForUser(String userId, String realmRefName) {
        if (quantumModeConfig.isRemote()) {
            return remote().realmsForUser(userId).stream()
                .filter(a -> realmRefName.equals(a.getRealmRefName()))
                .filter(a -> !UserRealmRole.STATUS_SUSPENDED.equals(a.getStatus()))
                .findFirst()
                .map(UserRealmRole::getRoles)
                .orElse(List.of());
        }
        return userRealmRoleRepo.findActiveRolesForRealmWithIgnoreRules(
            userId, realmRefName, systemDirectory.systemRealmId());
    }

    /** Resolve an active user's complete assignment within one realm. */
    public Optional<UserRealmRole> assignmentForUser(String userId, String realmRefName) {
        return realmsForUser(userId).stream()
            .filter(assignment -> realmRefName.equals(assignment.getRealmRefName()))
            .filter(assignment -> !UserRealmRole.STATUS_SUSPENDED.equals(assignment.getStatus()))
            .findFirst();
    }

    /** Resolve an active tenant membership inside a realm. */
    public Optional<RealmTenantMembership> tenantInRealm(String realmRefName, String tenantId) {
        return membersOfRealm(realmRefName).stream()
            .filter(membership -> tenantId.equals(membership.getTenantId()))
            .filter(membership -> !RealmTenantMembership.PARTICIPATION_STATUS_SUSPENDED.equals(
                membership.getParticipationStatus()))
            .findFirst();
    }

    private ObjectId stableObjectId(String recordType, String first, String second) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                String.join("\u0000", recordType, first, second).getBytes(StandardCharsets.UTF_8));
            return new ObjectId(java.util.Arrays.copyOf(digest, 12));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for stable control-plane identities", exception);
        }
    }
}

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

    @ConfigProperty(name = "quantum.system-service.token")
    java.util.Optional<String> serviceToken;

    private volatile RemoteMembershipClient remoteClient;

    /** Phase C: in remote mode membership resolution goes to the control plane. */
    private RemoteMembershipClient remote() {
        if (remoteClient == null) {
            remoteClient = new RemoteMembershipClient(
                quantumModeConfig.systemServiceBaseUrl().orElseThrow(() ->
                    new IllegalStateException("quantum.system-service.base-url is required for remote membership resolution")),
                serviceToken);
        }
        return remoteClient;
    }

    /** All realm memberships for an org (owner and participant alike). */
    public List<RealmTenantMembership> membershipsForOrg(String orgRefName) {
        if (quantumModeConfig.isRemote()) {
            throw new IllegalStateException(
                "membershipsForOrg is not yet exposed by the control-plane API "
                + "(contracts/control-plane.openapi.yaml) — extend the contract before using "
                + "this query from a tier-2 deployment; failing loud, no local fallback.");
        }
        return membershipRepo.getListByQuery(0, -1,
            "organizationRefName:" + orgRefName);
    }

    /** Every org/account participating in a realm (the collaboration roster). */
    public List<RealmTenantMembership> membersOfRealm(String realmRefName) {
        if (quantumModeConfig.isRemote()) {
            return remote().membersOfRealm(realmRefName);
        }
        return membershipRepo.getListByQuery(0, -1,
            "realmRefName:" + realmRefName);
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
            return remote().upsertRealmMembership(membership);
        }
        String systemRealmId = systemDirectory.systemRealmId();
        Optional<RealmTenantMembership> existingMembership = membershipRepo.findByRealmRefNameWithIgnoreRules(
                systemRealmId, membership.getRealmRefName()).stream()
            .filter(existing -> java.util.Objects.equals(
                existing.getOrganizationRefName(), membership.getOrganizationRefName()))
            .findFirst();
        if (existingMembership.isPresent()) {
            RealmTenantMembership existing = existingMembership.get();
            existing.setAccountId(membership.getAccountId());
            existing.setTenantId(membership.getTenantId());
            existing.setMembershipRole(membership.getMembershipRole());
            existing.setParticipationStatus(membership.getParticipationStatus());
            if (membership.getDisplayName() != null) {
                existing.setDisplayName(membership.getDisplayName());
            }
            return membershipRepo.save(systemRealmId, existing);
        }
        membership.setId(stableObjectId("realm-membership", membership.getRealmRefName(),
            membership.getOrganizationRefName()));
        return membershipRepo.save(systemRealmId, membership);
    }

    /** The single owner membership of a realm (lifecycle/billing authority). */
    public Optional<RealmTenantMembership> ownerOfRealm(String realmRefName) {
        if (quantumModeConfig.isRemote()) {
            return remote().membersOfRealm(realmRefName).stream()
                .filter(m -> RealmTenantMembership.MEMBERSHIP_ROLE_OWNER.equals(m.getMembershipRole()))
                .findFirst();
        }
        return membershipRepo.getListByQuery(0, 2,
                "realmRefName:" + realmRefName
                + "&&membershipRole:" + RealmTenantMembership.MEMBERSHIP_ROLE_OWNER)
            .stream().findFirst();
    }

    /** A user's per-realm role assignments (the GitHub-model membership list). */
    public List<UserRealmRole> realmsForUser(String userId) {
        if (quantumModeConfig.isRemote()) {
            return remote().realmsForUser(userId);
        }
        return userRealmRoleRepo.getListByQuery(0, -1, "userId:" + userId);
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
            return remote().upsertUserRealmRole(assignment);
        }
        String systemRealmId = systemDirectory.systemRealmId();
        Optional<UserRealmRole> existingRole = userRealmRoleRepo.findAssignmentForRealmWithIgnoreRules(
                assignment.getUserId(), assignment.getRealmRefName(), systemRealmId);
        if (existingRole.isPresent()) {
            UserRealmRole existing = existingRole.get();
            existing.setRoles(assignment.getRoles());
            existing.setSponsoringOrgRefName(assignment.getSponsoringOrgRefName());
            existing.setStatus(assignment.getStatus());
            if (assignment.getDisplayName() != null) {
                existing.setDisplayName(assignment.getDisplayName());
            }
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
        return userRealmRoleRepo.getListByQuery(0, 1,
                "userId:" + userId + "&&realmRefName:" + realmRefName)
            .stream()
            .filter(a -> !UserRealmRole.STATUS_SUSPENDED.equals(a.getStatus()))
            .findFirst()
            .map(UserRealmRole::getRoles)
            .orElse(List.of());
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

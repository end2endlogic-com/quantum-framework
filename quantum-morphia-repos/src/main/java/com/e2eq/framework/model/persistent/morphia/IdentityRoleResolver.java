package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Centralized logic to resolve effective roles for a given identity (userId or role).
 * - If a userId is provided and a credential exists, the result includes:
 *   - roles from the access token (SecurityIdentity)
 *   - roles stored on the credential document
 *   - roles from any UserGroup memberships of the associated UserProfile
 * - If a role is provided (no credential), no expansion is performed.
 *
 * Note: Credentials and central directory groups are stored in the configured
 * system realm. Tenant-local UserProfile/UserGroup lookup remains only as a
 * compatibility fallback for embedded auth mode.
 */
@ApplicationScoped
public class IdentityRoleResolver {

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    UserGroupRepo userGroupRepo;

    @Inject
    UserRealmRoleRepo userRealmRoleRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    /**
     * Resolve the effective roles for an already resolved credential, also considering the access token roles
     * and user group memberships. This delegates to the centralized provenance builder to avoid duplicated logic.
     * Note: This method does not perform a realm lookup by userId; callers should ensure the identity corresponds
     * to the current principal when intending to include TOKEN roles.
     * @deprecated Use {@link #resolveEffectiveRoles(SecurityIdentity, CredentialUserIdPassword, String)} with explicit realm
     */
    @Deprecated
    public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential) {
        // Fallback: use credential's domain context realm if available
        String realm = (credential != null && credential.getDomainContext() != null)
            ? credential.getDomainContext().getDefaultRealm()
            : null;
        return resolveEffectiveRoles(identity, credential, realm);
    }

    /**
     * Resolve the effective roles for an already resolved credential within a specific realm,
     * also considering the access token roles and user group memberships.
     * @param identity the security identity from the token
     * @param credential the user's credential
     * @param realm the target realm for UserProfile/UserGroup lookups (e.g., from X-Realm header or credential's default)
     */
    public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential, String realm) {
        String authorityRealm = credentialHomeRealm(credential);
        return resolveEffectiveRoles(identity, credential, authorityRealm, realm);
    }

    /**
     * Resolve roles for an effective realm while keeping token and flat credential
     * authorities bound to the realm in which they were issued.
     */
    public String[] resolveEffectiveRoles(SecurityIdentity identity,
                                          CredentialUserIdPassword credential,
                                          String authorityRealm,
                                          String effectiveRealm) {
        return resolveEffectiveRoles(identity, credential, authorityRealm, effectiveRealm, null);
    }

    public String[] resolveEffectiveRoles(SecurityIdentity identity,
                                          CredentialUserIdPassword credential,
                                          String authorityRealm,
                                          String effectiveRealm,
                                          String applicationId) {
        String targetRealm = requireRealm(effectiveRealm);
        LinkedHashMap<String, EnumSet<RoleSource>> provenance = new LinkedHashMap<>();
        if (identity != null) {
            addRoles(provenance, identity.getRoles(), RoleSource.TOKEN);
        }
        if (credential != null) {
            addRoles(provenance, credential.getRoles(), RoleSource.CREDENTIAL);
        }

        if (credential != null) {
            try {
                userRealmRoleRepo.findActiveAssignmentForRealmWithIgnoreRules(
                                credential.getUserId(), targetRealm, envConfigUtils.getSystemRealm())
                        .ifPresent(assignment -> addRoles(provenance, assignment.getRoles(), RoleSource.REALM));

                addUserGroupRoles(provenance, credential, envConfigUtils.getSystemRealm(), applicationId);
                if (!sameRealm(targetRealm, envConfigUtils.getSystemRealm())) {
                    addUserGroupRoles(provenance, credential, targetRealm, applicationId);
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException(String.format(
                        "Unable to resolve role authorities for user '%s' in realm '%s'",
                        credential.getUserId(), targetRealm), e);
            }
        }

        if (provenance.isEmpty()) return new String[]{"ANONYMOUS"};
        return provenance.keySet().toArray(new String[0]);
    }

    /**
     * Given an identity that may be a userId or a role, return the set of identities to use for permission evaluation.
     * If identity resolves to a credential (userId), include the user's implied roles; otherwise, treat it as a role.
     */
    public Set<String> resolveRolesForIdentity(String identity, String realm, SecurityIdentity securityIdentity) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (identity == null || identity.isBlank()) return out;
        out.add(identity);
        Map<String, EnumSet<RoleSource>> provenance = resolveRoleSources(identity, realm, securityIdentity);
        if (!provenance.isEmpty()) {
            out.addAll(provenance.keySet());
        }
        return out;
    }

    /**
     * Resolve role provenance (role -> sources) for a given identity within a realm.
     * If the identity matches a credential, include sources from IDP, CREDENTIAL, and USERGROUP.
     * If no credential exists, only IDP roles can be derived.
     *
     * Note: Credentials and central directory groups are looked up from the
     * configured system realm. Tenant-local UserProfile/UserGroup lookup remains
     * only as a compatibility fallback for embedded auth mode.
     */
    public Map<String, EnumSet<RoleSource>> resolveRoleSources(String identity, String realm, SecurityIdentity securityIdentity) {
        return resolveRoleSources(identity, realm, null, securityIdentity);
    }

    public Map<String, EnumSet<RoleSource>> resolveRoleSources(String identity,
                                                               String realm,
                                                               String applicationId,
                                                               SecurityIdentity securityIdentity) {
        LinkedHashMap<String, EnumSet<RoleSource>> out = new LinkedHashMap<>();
        String targetRealm = requireRealm(realm);
        String systemRealm = envConfigUtils.getSystemRealm();
        Optional<CredentialUserIdPassword> credential = credentialRepo.findByUserId(identity, systemRealm, true);
        String authorityRealm = identityRealm(securityIdentity);
        if (authorityRealm == null && credential.isPresent()) {
            authorityRealm = credentialHomeRealm(credential.get());
        }

        if (currentIdentityMatches(identity, securityIdentity)) {
            addRoles(out, securityIdentity.getRoles(), RoleSource.TOKEN);
        }

        if (credential.isPresent()) {
            CredentialUserIdPassword cred = credential.get();
            addRoles(out, cred.getRoles(), RoleSource.CREDENTIAL);
            userRealmRoleRepo.findActiveAssignmentForRealmWithIgnoreRules(
                            cred.getUserId(), targetRealm, systemRealm)
                    .ifPresent(assignment -> addRoles(out, assignment.getRoles(), RoleSource.REALM));

            addUserGroupRoles(out, cred, systemRealm, applicationId);
            if (!sameRealm(targetRealm, systemRealm)) {
                addUserGroupRoles(out, cred, targetRealm, applicationId);
            }
        }
        return out;
    }

    private void addUserGroupRoles(Map<String, EnumSet<RoleSource>> target,
                                   CredentialUserIdPassword credential,
                                   String groupRealm,
                                   String applicationId) {
        if (credential == null || credential.getSubject() == null || groupRealm == null || groupRealm.isBlank()) {
            return;
        }
        Optional<UserProfile> profile = userProfileRepo.getBySubject(groupRealm, credential.getSubject());
        if (profile.isEmpty()) {
            return;
        }
        List<UserGroup> groups = userGroupRepo.findByUserProfileRefWithIgnoreRules(
                groupRealm, profile.get().createEntityReference());
        if (groups == null) {
            return;
        }
        for (UserGroup group : groups) {
            if (group != null && groupAppliesToApplication(group, applicationId)) {
                addRoles(target, group.getRoles(), RoleSource.USERGROUP);
            }
        }
    }

    static boolean groupAppliesToApplication(UserGroup group, String applicationId) {
        String groupApplication = group.getApplicationId();
        if (applicationId == null || applicationId.isBlank()) {
            return true;
        }
        return groupApplication != null
                && ("*".equals(groupApplication.trim())
                    || groupApplication.trim().equalsIgnoreCase(applicationId.trim()));
    }

    private String credentialHomeRealm(CredentialUserIdPassword credential) {
        if (credential == null || credential.getDomainContext() == null) return null;
        return credential.getDomainContext().getDefaultRealm();
    }

    private String identityRealm(SecurityIdentity identity) {
        if (identity == null) return null;
        Object value = identity.getAttribute("realm");
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private boolean currentIdentityMatches(String identity, SecurityIdentity securityIdentity) {
        if (identity == null || securityIdentity == null || securityIdentity.getPrincipal() == null) return false;
        if (identity.equals(securityIdentity.getPrincipal().getName())) return true;
        Object userId = securityIdentity.getAttribute("userId");
        return userId instanceof String s && identity.equals(s);
    }

    private String requireRealm(String realm) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("A non-blank realm is required for role resolution");
        }
        return realm.trim();
    }

    private boolean sameRealm(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private void addRoles(Map<String, EnumSet<RoleSource>> target,
                          Collection<String> roles,
                          RoleSource source) {
        if (roles == null) return;
        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            target.computeIfAbsent(role, ignored -> EnumSet.noneOf(RoleSource.class)).add(source);
        }
    }

    private void addRoles(Map<String, EnumSet<RoleSource>> target,
                          String[] roles,
                          RoleSource source) {
        addRoles(target, roles == null ? null : Arrays.asList(roles), source);
    }

    /** Small immutable DTO bundling the union of roles with their assignments. */
    public static final class RoleResolutionResult {
        private final java.util.Set<String> roles;
        private final java.util.List<RoleAssignment> assignments;

        public RoleResolutionResult(java.util.Set<String> roles, java.util.List<RoleAssignment> assignments) {
            this.roles = (roles == null) ? java.util.Set.of() : java.util.Set.copyOf(roles);
            this.assignments = (assignments == null) ? java.util.List.of() : java.util.List.copyOf(assignments);
        }

        public java.util.Set<String> getRoles() { return roles; }
        public java.util.List<RoleAssignment> getAssignments() { return assignments; }
    }

    /**
     * Convenience API for plugins/framework callers: build both the union of roles and the
     * corresponding roleAssignments (with provenance) in one call. The returned roles set is the
     * key set of the provenance map and therefore never includes the raw identity/userId.
     */
    public RoleResolutionResult buildRoleResolution(String identity, String realm, SecurityIdentity securityIdentity) {
        Map<String, EnumSet<RoleSource>> provenance = resolveRoleSources(identity, realm, securityIdentity);
        // Union of roles is the provenance key set; avoid nulls and blanks
        java.util.LinkedHashSet<String> roles = new java.util.LinkedHashSet<>();
        for (String r : provenance.keySet()) {
            if (r != null && !r.isBlank()) roles.add(r);
        }
        java.util.List<RoleAssignment> assignments = toAssignments(provenance);
        return new RoleResolutionResult(roles, assignments);
    }

    public RoleResolutionResult buildRoleResolution(String identity,
                                                     String realm,
                                                     String applicationId,
                                                     SecurityIdentity securityIdentity) {
        Map<String, EnumSet<RoleSource>> provenance = resolveRoleSources(
                identity, realm, applicationId, securityIdentity);
        java.util.LinkedHashSet<String> roles = new java.util.LinkedHashSet<>();
        for (String role : provenance.keySet()) {
            if (role != null && !role.isBlank()) roles.add(role);
        }
        return new RoleResolutionResult(roles, toAssignments(provenance));
    }

    /** Utility to convert provenance map to role assignments list. */
    public List<RoleAssignment> toAssignments(Map<String, EnumSet<RoleSource>> provenance) {
        if (provenance == null || provenance.isEmpty()) return List.of();
        return provenance.entrySet().stream()
                .map(e -> new RoleAssignment(e.getKey(), e.getValue()))
                .toList();
    }
}

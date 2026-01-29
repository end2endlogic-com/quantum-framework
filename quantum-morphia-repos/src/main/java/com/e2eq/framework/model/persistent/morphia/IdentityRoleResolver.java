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
 * Note: Credentials are always stored in system-com (global). The realm parameter
 * is used for UserProfile/UserGroup lookups in the tenant's database.
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
        // Best-effort identity value for provenance: prefer principal name; otherwise credential userId
        String identityValue = null;
        if (identity != null && identity.getPrincipal() != null) {
            identityValue = identity.getPrincipal().getName();
        }
        if ((identityValue == null || identityValue.isBlank()) && credential != null) {
            identityValue = credential.getUserId();
        }

        // We do not have realm here; group/credential expansion below uses the subject from the provided credential.
        // Build provenance inline to ensure RoleSource is constructed while roles are aggregated.
        LinkedHashMap<String, EnumSet<RoleSource>> provenance = new LinkedHashMap<>();

        // TOKEN roles: included only if identityValue equals the principal (i.e., checking current user)
        if (identity != null && identityValue != null && identity.getPrincipal() != null
                && identityValue.equals(identity.getPrincipal().getName())) {
            for (String r : identity.getRoles()) {
                if (r == null || r.isBlank()) continue;
                provenance.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.TOKEN);
            }
        }

        // CREDENTIAL roles
        if (credential != null && credential.getRoles() != null) {
            for (String r : credential.getRoles()) {
                if (r == null || r.isBlank()) continue;
                provenance.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.CREDENTIAL);
            }
        }

        // USERGROUP roles via UserProfile -> UserGroup definitions
        try {
            if (credential != null) {
                // Use the provided realm for UserProfile/UserGroup lookups
                // This ensures we query the correct tenant's datastore, not just system-com
                Log.debugf("IdentityRoleResolver: looking up UserProfile for subject=%s in realm=%s",
                    credential.getSubject(), realm);

                Optional<UserProfile> userProfileOpt;
                if (realm != null && !realm.isBlank()) {
                    userProfileOpt = userProfileRepo.getBySubject(realm, credential.getSubject());
                } else {
                    // Fallback to no-realm method (will use security context realm)
                    Log.warnf("IdentityRoleResolver: realm is null/blank, falling back to context realm for subject=%s",
                        credential.getSubject());
                    userProfileOpt = userProfileRepo.getBySubject(credential.getSubject());
                }
                if (userProfileOpt.isPresent()) {
                    Log.debugf("IdentityRoleResolver: UserProfile found for subject=%s, looking up UserGroups in realm=%s",
                        credential.getSubject(), realm);
                    // IMPORTANT: Pass the realm to findByUserProfileRef to query the correct database
                    // The security context may be different (e.g., system-com during login)
                    var groups = (realm != null && !realm.isBlank())
                        ? userGroupRepo.findByUserProfileRef(realm, userProfileOpt.get().createEntityReference())
                        : userGroupRepo.findByUserProfileRef(userProfileOpt.get().createEntityReference());
                    if (groups != null && !groups.isEmpty()) {
                        Log.debugf("IdentityRoleResolver: found %d UserGroups for subject=%s", groups.size(), credential.getSubject());
                        for (UserGroup g : groups) {
                            if (g == null || g.getRoles() == null) continue;
                            Log.debugf("IdentityRoleResolver: UserGroup '%s' has roles: %s", g.getRefName(), java.util.Arrays.toString(g.getRoles().toArray()));
                            for (String r : g.getRoles()) {
                                if (r == null || r.isBlank()) continue;
                                provenance.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.USERGROUP);
                            }
                        }
                    } else {
                        Log.debugf("IdentityRoleResolver: no UserGroups found for subject=%s", credential.getSubject());
                    }
                }
                else {
                    // No matching UserProfile found, assume anonymous
                    Log.warnf("No matching UserProfile found for subject=%s in realm=%s when attempting to resolve groups in role resolver",
                        credential.getSubject(), realm);
                }
            }
        } catch (Exception e) {
            Log.warn("Failed to expand roles via user groups; continuing", e);
        }

        // Return the union
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
        try {
            // Build provenance centrally and reuse for union to avoid duplicated logic and inconsistent conditions
            Map<String, EnumSet<RoleSource>> provenance = resolveRoleSources(identity, realm, securityIdentity);
            if (!provenance.isEmpty()) {
                out.addAll(provenance.keySet());
            }
        } catch (Exception e) {
            Log.warnf(e, "Error resolving roles for identity %s in realm %s", identity, realm);
        }
        return out;
    }

    /**
     * Resolve role provenance (role -> sources) for a given identity within a realm.
     * If the identity matches a credential, include sources from IDP, CREDENTIAL, and USERGROUP.
     * If no credential exists, only IDP roles can be derived.
     *
     * Note: Credentials are always looked up from system-com (global). The realm parameter
     * is used for UserProfile/UserGroup lookups in the tenant's database.
     */
    public Map<String, EnumSet<RoleSource>> resolveRoleSources(String identity, String realm, SecurityIdentity securityIdentity) {
        LinkedHashMap<String, EnumSet<RoleSource>> out = new LinkedHashMap<>();

        Log.debugf("resolveRoleSources: identity=%s, realm=%s", identity, realm);

        // TOKEN roles from the current security identity (include when the current identity matches by principal name or augmented userId attribute)
        if (securityIdentity != null) {
            String principalName = (securityIdentity.getPrincipal() != null) ? securityIdentity.getPrincipal().getName() : null;
            String attrUserId = null;
            try {
                Object attr = securityIdentity.getAttribute("userId");
                if (attr instanceof String s) attrUserId = s;
            } catch (Throwable ignored) {}

            boolean matches = (identity != null) && (
                    (principalName != null && identity.equals(principalName)) ||
                    (attrUserId != null && identity.equals(attrUserId))
            );

            if (matches) {
                for (String r : securityIdentity.getRoles()) {
                    if (r == null || r.isEmpty()) continue;
                    out.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.TOKEN);
                }
            }
        }

        try {
            // IMPORTANT: Credentials are ALWAYS stored in system-com (global), not in tenant realms.
            // Use envConfigUtils.getSystemRealm() for credential lookup, regardless of the realm parameter.
            String systemRealm = envConfigUtils.getSystemRealm();
            Log.debugf("resolveRoleSources: looking up credential in systemRealm=%s for identity=%s", systemRealm, identity);

            var ocreds = credentialRepo.findByUserId(identity, systemRealm, true);
            if (ocreds.isPresent()) {
                var cred = ocreds.get();
                Log.debugf("resolveRoleSources: credential found for identity=%s, subject=%s", identity, cred.getSubject());

                // Credential roles
                if (cred.getRoles() != null) {
                    for (String r : cred.getRoles()) {
                        if (r == null || r.isEmpty()) continue;
                        out.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.CREDENTIAL);
                    }
                }
                // User group roles via profile (always unioned when credential exists)
                try {
                    // Use the provided realm to ensure we read the profile from the correct tenant datastore
                    // If realm is null/blank, fall back to the no-realm method (uses security context realm)
                    Log.debugf("resolveRoleSources: looking up UserProfile in realm=%s for subject=%s", realm, cred.getSubject());
                    Optional<UserProfile> userProfileOpt;
                    if (realm != null && !realm.isBlank()) {
                        userProfileOpt = userProfileRepo.getBySubject(realm, cred.getSubject());
                    } else {
                        Log.warnf("resolveRoleSources: realm is null/blank, falling back to context realm for subject=%s", cred.getSubject());
                        userProfileOpt = userProfileRepo.getBySubject(cred.getSubject());
                    }
                    if (userProfileOpt.isPresent()) {
                        Log.debugf("resolveRoleSources: UserProfile found for subject=%s, looking up UserGroups in realm=%s", cred.getSubject(), realm);
                        // IMPORTANT: Pass the realm to findByUserProfileRef to query the correct database
                        // The security context may be different (e.g., system-com during login)
                        // If realm is null/blank, fall back to the no-realm method
                        var groups = (realm != null && !realm.isBlank())
                            ? userGroupRepo.findByUserProfileRef(realm, userProfileOpt.get().createEntityReference())
                            : userGroupRepo.findByUserProfileRef(userProfileOpt.get().createEntityReference());
                        if (groups != null) {
                            Log.debugf("resolveRoleSources: found %d UserGroups for subject=%s", groups.size(), cred.getSubject());
                            for (UserGroup g : groups) {
                                if (g != null && g.getRoles() != null) {
                                    Log.debugf("resolveRoleSources: UserGroup '%s' has roles: %s", g.getRefName(), g.getRoles());
                                    for (String r : g.getRoles()) {
                                        if (r == null || r.isEmpty()) continue;
                                        out.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.USERGROUP);
                                    }
                                }
                            }
                        } else {
                            Log.debugf("resolveRoleSources: no UserGroups found for subject=%s", cred.getSubject());
                        }
                    } else {
                        Log.debugf("resolveRoleSources: no UserProfile found in realm=%s for subject=%s", realm, cred.getSubject());
                    }
                } catch (Exception e) {
                    Log.warn("Failed to expand user-group roles; continuing", e);
                }
            } else {
                Log.debugf("resolveRoleSources: no credential found in systemRealm=%s for identity=%s", systemRealm, identity);
            }
        } catch (Exception e) {
            Log.warnf(e, "Error resolving role sources for identity %s in realm %s", identity, realm);
        }

        Log.debugf("resolveRoleSources: returning roles=%s", out.keySet());
        return out;
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

    /** Utility to convert provenance map to role assignments list. */
    public List<RoleAssignment> toAssignments(Map<String, EnumSet<RoleSource>> provenance) {
        if (provenance == null || provenance.isEmpty()) return List.of();
        return provenance.entrySet().stream()
                .map(e -> new RoleAssignment(e.getKey(), e.getValue()))
                .toList();
    }
}

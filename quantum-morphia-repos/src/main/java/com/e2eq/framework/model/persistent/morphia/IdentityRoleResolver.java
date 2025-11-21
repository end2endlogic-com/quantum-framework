package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserGroup;
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
 */
@ApplicationScoped
public class IdentityRoleResolver {

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    UserProfileRepo userProfileRepo;

    @Inject
    UserGroupRepo userGroupRepo;

    /**
     * Resolve the effective roles for an already resolved credential, also considering the access token roles
     * and user group memberships.
     */
    public String[] resolveEffectiveRoles(SecurityIdentity identity, CredentialUserIdPassword credential) {
        Set<String> rolesSet = new LinkedHashSet<>();
        // roles from access token
        if (identity != null) {
            for (String role : identity.getRoles()) {
                if (role != null && !role.isEmpty()) rolesSet.add(role);
            }
        }
        // roles from credential
        if (credential != null && credential.getRoles() != null) {
            for (String r : credential.getRoles()) {
                if (r != null && !r.isEmpty()) rolesSet.add(r);
            }
        }
        // roles from user groups via user profile
        try {
            if (credential != null) {
                var userProfileOpt = userProfileRepo.getBySubject(credential.getSubject());
                if (userProfileOpt.isPresent()) {
                    var groups = userGroupRepo.findByUserProfileRef(userProfileOpt.get().createEntityReference());
                    if (groups != null) {
                        for (UserGroup g : groups) {
                            if (g != null && g.getRoles() != null) {
                                for (String r : g.getRoles()) {
                                    if (r != null) rolesSet.add(r);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("Failed to expand roles via user groups; continuing with token/credential roles", e);
        }
        if (rolesSet.isEmpty()) return new String[]{"ANONYMOUS"};
        return rolesSet.toArray(new String[0]);
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
            var ocreds = credentialRepo.findByUserId(identity, realm, true);
            if (ocreds.isPresent()) {
                String[] roles = resolveEffectiveRoles(securityIdentity, ocreds.get());
                if (roles != null) Collections.addAll(out, roles);
            } // else: treat as role, no expansion
        } catch (Exception e) {
            Log.warnf(e, "Error resolving roles for identity %s in realm %s", identity, realm);
        }
        return out;
    }

    /**
     * Resolve role provenance (role -> sources) for a given identity within a realm.
     * If the identity matches a credential, include sources from IDP, CREDENTIAL, and USERGROUP.
     * If no credential exists, only IDP roles can be derived.
     */
    public Map<String, EnumSet<RoleSource>> resolveRoleSources(String identity, String realm, SecurityIdentity securityIdentity) {
        LinkedHashMap<String, EnumSet<RoleSource>> out = new LinkedHashMap<>();

        // IDP roles from the current security identity (token)
        if (securityIdentity != null) {
            for (String r : securityIdentity.getRoles()) {
                if (r == null || r.isEmpty()) continue;
                out.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.IDP);
            }
        }

        try {
            var ocreds = credentialRepo.findByUserId(identity, realm, true);
            if (ocreds.isPresent()) {
                var cred = ocreds.get();
                // Credential roles
                if (cred.getRoles() != null) {
                    for (String r : cred.getRoles()) {
                        if (r == null || r.isEmpty()) continue;
                        out.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.CREDENTIAL);
                    }
                }
                // User group roles via profile
                try {
                    var userProfileOpt = userProfileRepo.getBySubject(cred.getSubject());
                    if (userProfileOpt.isPresent()) {
                        var groups = userGroupRepo.findByUserProfileRef(userProfileOpt.get().createEntityReference());
                        if (groups != null) {
                            for (UserGroup g : groups) {
                                if (g != null && g.getRoles() != null) {
                                    for (String r : g.getRoles()) {
                                        if (r == null || r.isEmpty()) continue;
                                        out.computeIfAbsent(r, k -> EnumSet.noneOf(RoleSource.class)).add(RoleSource.USERGROUP);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.warn("Failed to expand user-group roles; continuing", e);
                }
            }
        } catch (Exception e) {
            Log.warnf(e, "Error resolving role sources for identity %s in realm %s", identity, realm);
        }

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

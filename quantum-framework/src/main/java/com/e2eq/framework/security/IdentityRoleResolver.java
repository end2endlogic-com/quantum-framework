package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.rest.models.Role;
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
}

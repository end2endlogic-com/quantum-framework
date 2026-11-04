package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.system.membership.RealmMembershipService;
import io.quarkus.logging.Log;

import java.util.Locale;
import java.util.Optional;

/**
 * Roles of a delegated (trusted-issuer) principal whose token roles cannot be adopted as is:
 * after a realm switch, or in an application session entered with a token minted for another
 * application. Shared by {@link SecurityFilter} (PrincipalContext) and {@link RolesAugmentor}
 * ({@code @RolesAllowed}) so both authorization layers see the same roles.
 * See {@link SecurityFilter#delegatedRolesAfterRealmSwitch} for the rule.
 */
final class DelegatedSessionRoles {

    private DelegatedSessionRoles() {
    }

    static String[] resolve(SystemDirectory systemDirectory,
                            RealmMembershipService realmMembershipService,
                            String subject, String userId,
                            java.util.Collection<String> tokenRoles,
                            String targetRealm) {
        java.util.Set<String> flatRoles = authoritativeFlatRoles(systemDirectory, subject, userId);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (tokenRoles != null) {
            for (String role : tokenRoles) {
                if (role != null && flatRoles.contains(role.trim().toLowerCase(Locale.ROOT))) {
                    out.add(role);
                }
            }
        }
        realmMembershipService.assignmentForUser(userId, targetRealm)
                .map(com.e2eq.framework.model.security.UserRealmRole::getRoles)
                .ifPresent(list -> list.stream().filter(java.util.Objects::nonNull).forEach(out::add));
        if (out.isEmpty()) {
            return new String[]{"ANONYMOUS"};
        }
        return out.toArray(String[]::new);
    }

    /**
     * The flat (realm-independent) roles on the credential bound to {@code subject}, lower-cased;
     * empty when no authoritative credential is available for this subject and userId.
     */
    private static java.util.Set<String> authoritativeFlatRoles(SystemDirectory systemDirectory,
                                                                String subject, String userId) {
        if (subject == null || subject.isBlank() || systemDirectory == null) {
            return java.util.Set.of();
        }
        // The lookup is not issuer-scoped. That adds no exposure: only a trusted issuer can mint
        // these claims, and it can already assert any role in its signed realm without a switch.
        Optional<CredentialUserIdPassword> credential;
        try {
            credential = systemDirectory.findCredentialBySubject(subject);
        } catch (IllegalStateException e) {
            // Remote control-plane mode: credential material never crosses the seam.
            Log.debugf("Delegated realm switch: no authoritative flat roles for sub=%s (%s); "
                    + "token roles are not carried", subject, e.getMessage());
            return java.util.Set.of();
        }
        if (credential.isEmpty() || credential.get().getUserId() == null
                || !credential.get().getUserId().equalsIgnoreCase(userId)
                || credential.get().getRoles() == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> roles = new java.util.HashSet<>();
        for (String role : credential.get().getRoles()) {
            if (role != null && !role.isBlank()) {
                roles.add(role.trim().toLowerCase(Locale.ROOT));
            }
        }
        return roles;
    }
}

package com.e2eq.framework.model.securityrules;

import io.quarkus.security.identity.SecurityIdentity;

/**
 * {@code SecurityIdentity} attributes the principal source sets, so code that needs the caller's
 * realm reads resolved context instead of a token claim ({@code IDENTITY_ONLY_TOKENS_DESIGN.md}).
 */
public final class PrincipalIdentityAttributes {

    /**
     * The realm the identity's roles were resolved for: the realm Auth resolved
     * ({@code quantum.auth.principal-source=remote}), else the application-session or signed realm
     * the roles were taken for ({@code RolesAugmentor}, the embedded token provider). Absent when
     * the principal source knows no realm. Code that pairs {@code SecurityIdentity.getRoles()} with
     * a realm uses {@link #rolesRealm(SecurityIdentity)}.
     */
    public static final String ROLES_REALM = "rolesRealm";

    private PrincipalIdentityAttributes() {
    }

    /** The realm {@code identity}'s roles were resolved for, or null when none is known. */
    public static String rolesRealm(SecurityIdentity identity) {
        if (identity == null) {
            return null;
        }
        Object value = identity.getAttribute(ROLES_REALM);
        return value instanceof String realm && !realm.isBlank() ? realm.trim() : null;
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@ApplicationScoped
public class RolesAugmentor implements SecurityIdentityAugmentor {
    @Inject
    CredentialRepo credentialRepo;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    HttpHeaders httpHeaders;

    // See SecurityFilter: in an application session a token minted for another application
    // carries that application's roles, so its groups are not honored here either.
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quantum.security.session.application-id")
    Optional<String> sessionApplicationId = Optional.empty();

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quantum.security.session.realm")
    Optional<String> sessionRealm = Optional.empty();

    @Inject
    com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver identityRoleResolver;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        return Uni.createFrom().item(build(identity));
    }

    private Supplier<SecurityIdentity> build(SecurityIdentity identity) {
        if (identity.isAnonymous()) {
            return () -> identity;
        }

        boolean foreignApplicationToken = foreignApplicationToken(identity);
        QuarkusSecurityIdentity.Builder builder = foreignApplicationToken
                ? withoutTokenRoles(identity)
                : QuarkusSecurityIdentity.builder(identity);
        String originalPrincipal = identity.getPrincipal().getName();
        String contextRealm = null;
        String requestedRealm = null;

        // Check if HTTP request context is active
        try {
            contextRealm = httpHeaders.getHeaderString("X-Realm");
            requestedRealm = contextRealm;
            Log.debugf("RoleAugmentor overriding realm with ContextRealm: %s", contextRealm);
        } catch (ContextNotActiveException e) {
            Log.debug("No HTTP request context available, falling back to default realm");
        }

        // Fallback to system realm if contextRealm is not set
        if (contextRealm == null) {
            contextRealm = envConfigUtils.getSystemRealm();
        }

        // Derive roles from the validated token's groups claim. A DELEGATED identity (a trusted
        // central-issuer token whose subject has no local credential in this realm — the standard
        // multi-app case) carries its credential's roles in the JWT `groups`, not in a local
        // record. Without this, the coarse @RolesAllowed gate can't see the roles the token grants
        // (only the RuleContext policy engine, which reads the token directly, does), so it 403s.
        // Adding them here keeps both authz layers agreeing on the same role set.
        if (!foreignApplicationToken
                && identity.getPrincipal() instanceof org.eclipse.microprofile.jwt.JsonWebToken token) {
            java.util.Set<String> groups = token.getGroups();
            if (groups != null && !groups.isEmpty()) {
                builder.addRoles(new HashSet<>(groups));
                Log.debugf("RolesAugmentor: added %d role(s) from token groups for %s: %s",
                        groups.size(), originalPrincipal, groups);
            }
        }

        Optional<CredentialUserIdPassword> ocred = credentialRepo.findBySubject(originalPrincipal, contextRealm, true);
        if (ocred.isPresent()) {
            CredentialUserIdPassword cred = ocred.get();
            // 1) Add roles from credential
            builder.addRoles(new HashSet<>(List.of(cred.getRoles())));
            if (foreignApplicationToken) {
                // The session's roles, as SecurityFilter resolves them for the PrincipalContext.
                builder.addRoles(new HashSet<>(List.of(sessionRoles(identity, cred, requestedRealm))));
            }

            // 2) Expose preferred identity (userId) as an attribute without altering the JWT principal
            String userId = cred.getUserId();
            if (userId != null && !userId.isBlank()) {
                builder.addAttribute("userId", userId);
            }

            // 3) Preserve subjectId as an attribute/claim for downstream access
            // Also keep track of the original principal value for diagnostics
            if (cred.getSubject() != null && !cred.getSubject().isBlank()) {
                builder.addAttribute("subjectId", cred.getSubject());
                builder.addAttribute("sub", cred.getSubject());
            }
            builder.addAttribute("originalPrincipal", originalPrincipal);
        } else {
            Log.warnf("Could not find user %s in realm %s to augment roles", originalPrincipal, contextRealm);
        }

        // Add user role by default if not present
        if (foreignApplicationToken || !identity.getRoles().contains("user")) {
            builder.addRole("user");
        }

        return builder::build;
    }

    /**
     * Roles for (credential, session realm, session application) without the token's roles, with
     * SecurityFilter's realm precedence: an explicit X-Realm, else the configured session realm,
     * else the token's signed realm, else the credential's home realm.
     */
    private String[] sessionRoles(SecurityIdentity identity, CredentialUserIdPassword cred, String requestedRealm) {
        String home = cred.getDomainContext() == null ? null : cred.getDomainContext().getDefaultRealm();
        String signed = null;
        if (identity.getPrincipal() instanceof org.eclipse.microprofile.jwt.JsonWebToken token) {
            Object claim = token.getClaim("realm");
            signed = claim == null || claim.toString().isBlank() ? null : claim.toString().trim();
        }
        String authorityRealm = signed != null ? signed : home;
        String realm = requestedRealm != null && !requestedRealm.isBlank()
                ? requestedRealm.trim()
                : configured(sessionRealm) != null ? configured(sessionRealm) : authorityRealm;
        if (realm == null) {
            return new String[0];
        }
        return identityRoleResolver.resolveEffectiveRoles(
                null, cred, authorityRealm, realm, configured(sessionApplicationId));
    }

    private static String configured(Optional<String> value) {
        return value == null ? null : value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }

    boolean foreignApplicationToken(SecurityIdentity identity) {
        String configured = configured(sessionApplicationId);
        if (configured == null
                || !(identity.getPrincipal() instanceof org.eclipse.microprofile.jwt.JsonWebToken token)) {
            return false;
        }
        Object tokenType = token.getClaim(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM);
        if (tokenType != null && com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                .equalsIgnoreCase(tokenType.toString())) {
            return false;
        }
        Object azp = token.getClaim("azp");
        return azp == null || !configured.equalsIgnoreCase(azp.toString().trim());
    }

    /** The identity's principal, credentials and attributes, without the roles it arrived with. */
    static QuarkusSecurityIdentity.Builder withoutTokenRoles(SecurityIdentity identity) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addCredentials(identity.getCredentials())
                .addAttributes(identity.getAttributes())
                .setAnonymous(false);
        // Permission checkers derived from the token's roles are dropped with them (fail closed).
        return builder;
    }
}

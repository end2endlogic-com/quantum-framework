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

    // Application session (see SecurityFilter): when it moves a user token to another application
    // or realm, @RolesAllowed sees the session's roles, not the token's.
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quantum.security.session.application-id")
    Optional<String> sessionApplicationId = Optional.empty();

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quantum.security.session.realm")
    Optional<String> sessionRealm = Optional.empty();

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quantum.auth.trust-token-claims", defaultValue = "false")
    boolean trustTokenClaims;

    @Inject
    com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver identityRoleResolver;

    @Inject
    com.e2eq.framework.api.system.SystemDirectory systemDirectory;

    @Inject
    com.e2eq.framework.system.membership.RealmMembershipService realmMembershipService;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        return Uni.createFrom().item(build(identity));
    }

    private Supplier<SecurityIdentity> build(SecurityIdentity identity) {
        if (identity.isAnonymous()) {
            return () -> identity;
        }

        String originalPrincipal = identity.getPrincipal().getName();
        String contextRealm = null;

        // Check if HTTP request context is active
        try {
            contextRealm = httpHeaders.getHeaderString("X-Realm");
            Log.debugf("RoleAugmentor overriding realm with ContextRealm: %s", contextRealm);
        } catch (ContextNotActiveException e) {
            Log.debug("No HTTP request context available, falling back to default realm");
        }

        // In an application session whose application or realm differs from the token's, the
        // session's roles replace the token's, derived exactly as SecurityFilter derives them.
        java.util.Set<String> sessionRoles = applicationSessionRoles(identity, contextRealm);
        QuarkusSecurityIdentity.Builder builder = sessionRoles != null
                ? withoutTokenRoles(identity)
                : QuarkusSecurityIdentity.builder(identity);

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
        if (sessionRoles == null
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
            // 1) Add roles from credential (session roles already carry the ones that apply)
            if (sessionRoles == null) {
                builder.addRoles(new HashSet<>(List.of(cred.getRoles())));
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

        if (sessionRoles != null) {
            builder.addRoles(sessionRoles);
        }

        // Add user role by default if not present
        if (sessionRoles != null || !identity.getRoles().contains("user")) {
            builder.addRole("user");
        }

        return builder::build;
    }

    /**
     * The session's roles when an application session ({@code quantum.security.session.*}) moves
     * a user token to another application or realm than it was minted for; null when the token's
     * roles apply as before (no application session, a service token, or the token's own
     * application and realm). Mirrors SecurityFilter: realm = X-Realm, else the session realm,
     * else the token's signed realm (embedded: else the credential's home realm); delegated
     * principals ({@code quantum.auth.trust-token-claims}) use {@link DelegatedSessionRoles},
     * embedded ones {@code IdentityRoleResolver} on the system-realm credential.
     */
    java.util.Set<String> applicationSessionRoles(SecurityIdentity identity, String requestedRealm) {
        String sessionApplication = configured(sessionApplicationId);
        if (sessionApplication == null && configured(sessionRealm) == null) {
            return null;
        }
        if (!(identity.getPrincipal() instanceof org.eclipse.microprofile.jwt.JsonWebToken token)
                || serviceToken(token)) {
            return null;
        }
        boolean foreign = foreignApplicationToken(identity);
        String signed = claim(token, "realm");
        String selected = requestedRealm != null && !requestedRealm.isBlank()
                ? requestedRealm.trim()
                : configured(sessionRealm);

        if (trustTokenClaims) {
            String realm = selected != null ? selected : signed;
            boolean realmChanged = realm != null && !realm.equalsIgnoreCase(signed);
            if (!foreign && !realmChanged) {
                return null;
            }
            return roleSet(delegatedSessionRoles(token, realm));
        }

        String subject = claim(token, "sub");
        Optional<CredentialUserIdPassword> credential = subject == null
                ? Optional.empty()
                : credentialRepo.findBySubject(subject, envConfigUtils.getSystemRealm(), true);
        if (credential.isEmpty()) {
            // SecurityFilter refuses this request; grant nothing beyond "user" meanwhile.
            return foreign ? java.util.Set.of() : null;
        }
        CredentialUserIdPassword cred = credential.get();
        String home = cred.getDomainContext() == null ? null : cred.getDomainContext().getDefaultRealm();
        String authorityRealm = signed != null ? signed : home;
        String realm = selected != null ? selected : authorityRealm;
        if (realm == null) {
            return java.util.Set.of();
        }
        boolean realmChanged = !realm.equalsIgnoreCase(authorityRealm);
        if (!foreign && !realmChanged) {
            return null;
        }
        String applicationId = sessionApplication != null ? sessionApplication : claim(token, "azp");
        return roleSet(identityRoleResolver.resolveEffectiveRoles(
                foreign ? null : identity, cred, authorityRealm, realm, applicationId));
    }

    private String[] delegatedSessionRoles(org.eclipse.microprofile.jwt.JsonWebToken token, String realm) {
        if (realm == null || realmMembershipService == null) {
            return new String[0];
        }
        String subject = claim(token, "sub");
        // The claims SecurityFilter reads first for a delegated userId. Without them the provider's
        // own username mapping decides there, which this layer cannot reproduce: add nothing.
        String userId = claim(token, "userId");
        if (userId == null) userId = claim(token, "preferred_username");
        if (userId == null) {
            return new String[0];
        }
        return DelegatedSessionRoles.resolve(systemDirectory, realmMembershipService,
                subject, userId, token.getGroups(), realm);
    }

    private static java.util.Set<String> roleSet(String[] roles) {
        java.util.Set<String> out = new HashSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank() && !"ANONYMOUS".equals(role)) {
                out.add(role);
            }
        }
        return out;
    }

    private static boolean serviceToken(org.eclipse.microprofile.jwt.JsonWebToken token) {
        Object tokenType = token.getClaim(
                com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM);
        return tokenType != null && com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                .equalsIgnoreCase(tokenType.toString());
    }

    private static String claim(org.eclipse.microprofile.jwt.JsonWebToken token, String name) {
        Object value = token.getClaim(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
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
        if (serviceToken(token)) {
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

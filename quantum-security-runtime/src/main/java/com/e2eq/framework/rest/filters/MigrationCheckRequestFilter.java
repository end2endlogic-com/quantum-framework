package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.system.config.SystemRealmOwnership;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.security.Principal;
import java.util.Optional;

@Provider
public class MigrationCheckRequestFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    MigrationService migrationService;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    SystemRealmOwnership systemRealmOwnership;

    @Inject
    CredentialRepo credentialRepo;

    @Inject
    JsonWebToken jwt;

    @Inject
    SessionSelectionCoordinator sessionSelection;

    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    @Inject
    com.e2eq.framework.security.principal.RemotePrincipalSettings remotePrincipalSettings;

    @ConfigProperty(name = "quantum.database.migration.enabled", defaultValue = "true")
    boolean migrationCheckEnabled;

    @ConfigProperty(name = "quantum.auth.trust-token-claims", defaultValue = "false")
    boolean trustTokenClaims;

    @ConfigProperty(name = "quantum.security.session.realm")
    Optional<String> sessionRealm = Optional.empty();

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        boolean failRequest=false;
        DatabaseMigrationException ex = null;

        if (!migrationCheckEnabled) {
            return;
        }

        if (requestContext.getUriInfo().getPath().contains("hello")) {
            return;
        }

        try {
            String effectiveRealm = resolveMigrationRealm(requestContext);
            checkOwnedRealm(effectiveRealm);
           checkOwnedRealm(envConfigUtils.getSystemRealm());

        } catch (DatabaseMigrationException e) {
            Log.warn("!! Migration check failed: " + e.getMessage());
            Log.warnf(" Execution of Request:%s caused this error", requestContext.getUriInfo().getPath());
            failRequest=true;
            ex = e;
        }

        if (failRequest && (requestContext.getUriInfo().getPath().contains("login") ||
                  requestContext.getUriInfo().getPath().contains("migration"))) {
            try {
                checkOwnedRealm(envConfigUtils.getSystemRealm());
            } catch (DatabaseMigrationException e) {
                Log.warn("!! System Migration check failed: " + e.getMessage());
                Log.warnf(" Execution of Request:%s caused this error", requestContext.getUriInfo().getPath());
            }
            failRequest=false;
        }

        if (failRequest) {
           throw ex;
        }
    }

    /** Remote services validate their application schema, not the control plane's schema. */
    private void checkOwnedRealm(String realm) {
        if (java.util.Objects.equals(realm, envConfigUtils.getSystemRealm())
                && !systemRealmOwnership.ownedLocally()) {
            return;
        }
        migrationService.checkInitialized(realm);
    }

    String resolveMigrationRealm(ContainerRequestContext requestContext) {
        // Remote mode: the realm is the one Auth resolved for this request (X-Realm included),
        // never the token's realm claim. A refusal was already answered by RemotePrincipalRefusalFilter.
        if (remotePrincipalSettings != null && remotePrincipalSettings.remote()
                && identity != null && !identity.isAnonymous()
                && identity.getPrincipal() instanceof JsonWebToken token) {
            return RemotePrincipals.resolve(remotePrincipalSettings, routingContext, token,
                    requestContext::getHeaderString, true).getDefaultRealm();
        }
        String realmOverride = sessionSelection != null && sessionSelection.enabled()
                ? sessionSelection.realm(routingContext, identity, requestContext.getHeaderString("X-Realm"),
                    sessionRealm.map(String::trim).filter(value -> !value.isEmpty()).orElse(null))
                : requestContext.getHeaderString("X-Realm");
        if (realmOverride != null && !realmOverride.isBlank()) {
            Log.debug("Realm override found: " + realmOverride);
            return realmOverride;
        }

        if (trustTokenClaims && identity != null && !identity.isAnonymous()
                && identity.getPrincipal() instanceof JsonWebToken token) {
            Object kind = token.getClaim(com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM);
            if (kind != null && com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders.SERVICE_TOKEN_TYPE
                    .equalsIgnoreCase(kind.toString())) {
                return envConfigUtils.getSystemRealm();
            }
            String selected = sessionSelection != null && sessionSelection.enabled() ? null
                    : sessionRealm.map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
            if (selected != null) return selected;
            Object signedRealm = token.getClaim("realm");
            if (signedRealm == null || signedRealm.toString().isBlank()) {
                throw new jakarta.ws.rs.ForbiddenException("Trusted-issuer token is missing the required realm claim");
            }
            return signedRealm.toString().trim();
        }

        String authenticatedRealm = resolveAuthenticatedPrincipalRealm();
        if (authenticatedRealm != null && !authenticatedRealm.isBlank()) {
            Log.debugf("Using authenticated principal default realm for migration check: %s", authenticatedRealm);
            return authenticatedRealm;
        }

        return envConfigUtils.getDefaultRealm();
    }

    private String resolveAuthenticatedPrincipalRealm() {
        if (identity == null || identity.isAnonymous() || credentialRepo == null || envConfigUtils == null) {
            return null;
        }

        String systemRealm = envConfigUtils.getSystemRealm();

        Principal principal = identity.getPrincipal();
        String principalName = principal != null ? principal.getName() : null;

        String realm = realmFromCredentialLookup(principalName, systemRealm, true);
        if (realm != null) {
            return realm;
        }

        realm = realmFromCredentialLookup(principalName, systemRealm, false);
        if (realm != null) {
            return realm;
        }

        if (jwt != null) {
            try {
                String subject = jwt.getClaim("sub");
                realm = realmFromCredentialLookup(subject, systemRealm, false);
                if (realm != null) {
                    return realm;
                }
                realm = realmFromCredentialLookup(subject, systemRealm, true);
                if (realm != null) {
                    return realm;
                }
            } catch (Exception e) {
                Log.debugf("Unable to resolve JWT subject for migration realm fallback: %s", e.getMessage());
            }
        }

        return null;
    }

    private String realmFromCredentialLookup(String identityValue, String systemRealm, boolean lookupByUserId) {
        if (identityValue == null || identityValue.isBlank()) {
            return null;
        }

        try {
            Optional<CredentialUserIdPassword> credential = lookupByUserId
                ? credentialRepo.findByUserId(identityValue, systemRealm, true)
                : credentialRepo.findBySubject(identityValue, systemRealm, true);
            return credential
                .map(CredentialUserIdPassword::getDomainContext)
                .map(dc -> dc.getDefaultRealm())
                .filter(realm -> !realm.isBlank())
                .orElse(null);
        } catch (Exception e) {
            Log.debugf("Unable to resolve realm from credential lookup (%s=%s): %s",
                lookupByUserId ? "userId" : "subject",
                identityValue,
                e.getMessage());
            return null;
        }
    }
}

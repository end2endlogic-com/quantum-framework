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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Typed refusal code for a request whose realm this service has not migrated. */
    public static final String REALM_NOT_MIGRATED = "REALM_NOT_MIGRATED";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!migrationCheckEnabled) {
            return;
        }

        if (requestContext.getUriInfo().getPath().contains("hello")) {
            return;
        }

        String checkedRealm = null;
        DatabaseMigrationException ex = null;
        try {
            checkedRealm = resolveMigrationRealm(requestContext);
            checkOwnedRealm(checkedRealm);
            checkedRealm = envConfigUtils.getSystemRealm();
            checkOwnedRealm(checkedRealm);
        } catch (DatabaseMigrationException e) {
            Log.warnf("!! Migration check failed for realm %s: %s", checkedRealm, e.getMessage());
            Log.warnf(" Execution of Request:%s caused this error", requestContext.getUriInfo().getPath());
            ex = e;
        }

        if (ex != null && (requestContext.getUriInfo().getPath().contains("login") ||
                  requestContext.getUriInfo().getPath().contains("migration"))) {
            try {
                checkOwnedRealm(envConfigUtils.getSystemRealm());
            } catch (DatabaseMigrationException e) {
                Log.warn("!! System Migration check failed: " + e.getMessage());
                Log.warnf(" Execution of Request:%s caused this error", requestContext.getUriInfo().getPath());
            }
            ex = null;
        }

        if (ex != null) {
            throw realmNotMigrated(checkedRealm, ex);
        }
    }

    /**
     * The request's realm has no current database version in this service: a typed 409
     * {@code REALM_NOT_MIGRATED} naming the realm and what the migration check found, never an
     * untyped 500. In remote principal mode the realm is the one Auth resolved for the request
     * (the identity's home realm when the application owns none), so the body says so.
     */
    static WebApplicationException realmNotMigrated(String realm, DatabaseMigrationException cause) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", REALM_NOT_MIGRATED);
        body.put("message", "Realm " + realm + " is not migrated in this service; run its migrations, or "
                + "make the request in a realm this service serves");
        body.put("realm", realm);
        body.put("migration", cause.getMessage());
        Response response = Response.status(Response.Status.CONFLICT)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store")
                .entity(body)
                .build();
        return new WebApplicationException(cause.getMessage(), cause, response);
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
            // The realm the roles augmentor resolved the identity's roles for (the signed realm on
            // this claims path), not a claim read of our own.
            String signedRealm = com.e2eq.framework.model.securityrules.PrincipalIdentityAttributes.rolesRealm(identity);
            if (signedRealm == null) {
                throw new jakarta.ws.rs.ForbiddenException("Trusted-issuer token is missing the required realm claim");
            }
            return signedRealm;
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

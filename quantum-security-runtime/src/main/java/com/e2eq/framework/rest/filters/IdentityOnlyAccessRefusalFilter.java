package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSettings;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;


/**
 * A token contract v2 user access token on a receiver that resolves locally (not remote mode):
 * answers a refused or unresolvable token with its typed status, code and body before the resource
 * is matched, as {@link RemotePrincipalRefusalFilter} does in remote mode. Without it a
 * {@code @RolesAllowed} resource would answer a bare 403, since the eager role check runs before
 * SecurityFilter and {@link RolesAugmentor} leaves a refused identity no roles. The refusal is the
 * augmentor's own, left in the routing context: this filter runs on the event loop and never
 * resolves or reads the credential store itself. Requires proactive authentication (the default),
 * as remote mode does; without it the augmentor has not run yet and SecurityFilter refuses later.
 * Tokens of any other kind pass untouched.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class IdentityOnlyAccessRefusalFilter implements ContainerRequestFilter {

    @Inject
    RemotePrincipalSettings remoteSettings;

    @Inject
    SecurityIdentity identity;

    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    // With lazy authentication, reading the identity here would authenticate every request that
    // carries a bearer, even on public endpoints, and the augmentor has not run: stay out.
    @ConfigProperty(name = "quarkus.http.auth.proactive", defaultValue = "true")
    boolean proactiveAuthentication = true;

    @Override
    public void filter(ContainerRequestContext request) {
        if (!proactiveAuthentication || (remoteSettings != null && remoteSettings.remote())
                || routingContext == null || request.getUriInfo().getPath().contains("hello")) {
            return;
        }
        if (identity == null || identity.isAnonymous()
                || !(identity.getPrincipal() instanceof JsonWebToken token)
                || !IdentityOnlyAccessTokens.isAccessToken(token)) {
            return;
        }
        Object refused = routingContext.get(IdentityOnlyAccessTokens.REFUSAL_KEY);
        if (refused instanceof RemotePrincipalResolutionException typed) {
            request.abortWith(typed.getResponse());
        }
    }
}

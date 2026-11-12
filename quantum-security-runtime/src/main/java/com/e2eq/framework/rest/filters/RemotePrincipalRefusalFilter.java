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
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Remote mode: answers a refused or unresolvable bearer with Auth's status, code and body before
 * the resource is matched. Without it a {@code @RolesAllowed} resource would answer a bare 403,
 * since the eager role check runs before SecurityFilter (the augmentor leaves a refused identity
 * no roles). The resolution is the augmentor's own, shared through the request's state.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION)
public class RemotePrincipalRefusalFilter implements ContainerRequestFilter {

    @Inject
    RemotePrincipalSettings settings;

    @Inject
    SecurityIdentity identity;

    @Inject
    io.vertx.ext.web.RoutingContext routingContext;

    @Override
    public void filter(ContainerRequestContext request) {
        if (settings == null || !settings.remote() || request.getUriInfo().getPath().contains("hello")) {
            return;
        }
        // Reading the identity authenticates the request when authentication is not proactive.
        if (identity == null || identity.isAnonymous()
                || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return;
        }
        try {
            RemotePrincipals.resolve(settings, routingContext, token, request::getHeaderString, true);
        } catch (RemotePrincipalResolutionException refused) {
            request.abortWith(refused.getResponse());
        } catch (RuntimeException failed) {
            request.abortWith(RemotePrincipalResolutionException
                    .unavailable("Remote principal resolution failed: " + failed).getResponse());
        }
    }
}

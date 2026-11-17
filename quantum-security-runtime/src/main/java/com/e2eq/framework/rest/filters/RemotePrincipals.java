package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalRequest;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSettings;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.function.UnaryOperator;

/**
 * Remote-mode resolution of a verified JWT, shared by the roles augmentor, the refusal filter, the
 * migration check and SecurityFilter. Each request asks the source once per distinct question
 * (see {@link RequestAuthorizationState#remotePrincipal}).
 */
final class RemotePrincipals {

    private RemotePrincipals() {
    }

    /**
     * The principal for {@code token}. {@code tenantSelectionOptional} is true for questions asked
     * before the resource is matched (roles, realm), whose answers do not depend on the tenant;
     * SecurityFilter asks with the resource's own tenant-selection rule.
     */
    static PrincipalContext resolve(RemotePrincipalSettings settings, RoutingContext routing, JsonWebToken token,
                                    UnaryOperator<String> header, boolean tenantSelectionOptional) {
        String bearer = token.getRawToken();
        if (bearer == null || bearer.isBlank()) {
            throw RemotePrincipalResolutionException.of(PrincipalResolutionError.PRINCIPAL_UNKNOWN,
                    "The verified token has no raw encoding to present to Auth");
        }
        RemotePrincipalRequest request = RemotePrincipalRequest.fromHeaders(bearer, claim(token, "iss"),
                claim(token, "sub"), settings.applicationId(), header, tenantSelectionOptional);
        return RequestAuthorizationState.remotePrincipal(routing, request, settings.source());
    }

    private static String claim(JsonWebToken token, String name) {
        Object value = token.getClaim(name);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A role check that failed because the roles augmentor refused the requested realm (its
 * {@code X-Realm} is not a refName or names no registered realm, so token roles were dropped)
 * answers the typed 403 {@code REALM_NOT_AUTHORIZED}, as every other endpoint does for that
 * request. Any other role refusal stays a plain 403.
 */
@Provider
public class RefusedRealmForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Inject
    SecurityIdentity securityIdentity;

    @Override
    public Response toResponse(ForbiddenException exception) {
        // RealmRefNames.REFUSED_REALM_ATTRIBUTE, a server-set identity attribute (not a token claim).
        if (securityIdentity != null && !securityIdentity.isAnonymous()
                && securityIdentity.getAttribute("quantum-realm-refused") != null) {
            return RemotePrincipalResolutionException.of(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                    "X-Realm names no registered realm").getResponse();
        }
        return Response.status(Response.Status.FORBIDDEN).build();
    }
}

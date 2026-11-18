package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.UnregisteredRealmException;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A request that reached a datastore for a realm no one registered is refused 403
 * {@code REALM_NOT_AUTHORIZED}, the same typed body as every realm refusal; nothing was created.
 */
@Provider
public class UnregisteredRealmExceptionMapper implements ExceptionMapper<UnregisteredRealmException> {

    @Override
    public Response toResponse(UnregisteredRealmException exception) {
        Log.debugf("Unregistered realm refused: %s", exception.getMessage());
        return RemotePrincipalResolutionException.of(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                exception.getMessage()).getResponse();
    }
}

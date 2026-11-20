package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.RealmDatabaseNames;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A request that reached a datastore with a name that cannot be a realm (a reserved database or a
 * name not of a refName's shape) answers the typed 403 {@code REALM_NOT_AUTHORIZED} the request
 * boundary gives, never a 500. Nothing was opened.
 */
@Provider
public class InvalidRealmDatabaseExceptionMapper implements ExceptionMapper<RealmDatabaseNames.InvalidRealmDatabaseException> {

    @Override
    public Response toResponse(RealmDatabaseNames.InvalidRealmDatabaseException exception) {
        return RemotePrincipalResolutionException.of(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                exception.getMessage()).getResponse();
    }
}

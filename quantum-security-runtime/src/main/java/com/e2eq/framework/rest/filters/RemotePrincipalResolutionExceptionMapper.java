package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Answers a remote principal refusal with its own status and {@code {"code", "message"}} body.
 * Without it the generic {@code RuntimeException} mapper would rewrite the body and drop the code.
 */
@Provider
public class RemotePrincipalResolutionExceptionMapper implements ExceptionMapper<RemotePrincipalResolutionException> {

    @Override
    public Response toResponse(RemotePrincipalResolutionException exception) {
        if (exception.status() >= 500) {
            Log.warnf("Remote principal resolution unavailable: %s", exception.getMessage());
        } else {
            Log.debugf("Remote principal resolution refused (%s): %s", exception.code(), exception.getMessage());
        }
        return exception.getResponse();
    }
}

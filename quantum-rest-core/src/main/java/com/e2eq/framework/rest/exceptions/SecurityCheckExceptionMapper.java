package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.rest.models.RestError;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A policy denial reaching a REST boundary (a rule refusing an action, or a write filter refusing
 * a record outside the caller's data domain) is a 403 naming the refusal, not an unexpected 500.
 */
@Provider
public class SecurityCheckExceptionMapper implements ExceptionMapper<SecurityCheckException> {

    /** The status message of every policy denial. */
    public static final String CODE = "SECURITY_POLICY_DENIED";

    @Override
    public Response toResponse(SecurityCheckException exception) {
        Log.warnf("Policy denied the request: %s", exception.getMessage());
        RestError error = RestError.builder()
                .status(Response.Status.FORBIDDEN.getStatusCode())
                .statusMessage(CODE)
                .reasonMessage(exception.getMessage())
                .build();
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON).entity(error).build();
    }
}

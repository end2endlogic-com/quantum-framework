package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps BadRequestException to a 400 Bad Request response.
 * This ensures that validation errors and malformed requests
 * return proper 400 status codes with informative error messages.
 */
@Provider
public class BadRequestExceptionMapper implements ExceptionMapper<BadRequestException> {
    @Override
    public Response toResponse(BadRequestException exception) {
        // Log at DEBUG level (bad request is a client error, not a server error)
        ExceptionLoggingUtils.logDebug(exception, "Bad request");

        RestError error = RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.BAD_REQUEST.getStatusCode());
        error.setReasonMessage(String.format("Bad request: %s", exception.getMessage()));
        error.setDebugMessage(ExceptionLoggingUtils.getStackTrace(exception));

        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}

package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {
    @Override
    public Response toResponse(NotFoundException exception) {
        // Log at DEBUG level (not found is a normal client error, not a server error)
        ExceptionLoggingUtils.logDebug(exception, "Resource not found");
        
        RestError error = RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.NOT_FOUND.getStatusCode());
        error.setReasonMessage(String.format("The URL was not found: %s", exception.getMessage()));
        error.setDebugMessage(ExceptionLoggingUtils.getStackTrace(exception));

        return Response.status(Response.Status.NOT_FOUND).entity(error).build();
    }
}

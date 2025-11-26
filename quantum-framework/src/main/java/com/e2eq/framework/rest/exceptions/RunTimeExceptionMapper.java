package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class RunTimeExceptionMapper implements jakarta.ws.rs.ext.ExceptionMapper<RuntimeException> {
    @Inject
    ObjectMapper mapper;

    @Override
    public Response toResponse(RuntimeException exception) {
        // Use proper logging instead of printStackTrace
        ExceptionLoggingUtils.logError(exception, "An unexpected / uncaught exception occurred");

        RestError error = RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setReasonMessage("An unexpected / uncaught exception occurred");
        error.setDebugMessage(ExceptionLoggingUtils.getStackTrace(exception));

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}

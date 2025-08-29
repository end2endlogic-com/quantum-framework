package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;

@Provider
public class RunTimeExceptionMapper implements jakarta.ws.rs.ext.ExceptionMapper<RuntimeException> {
    @Inject
    ObjectMapper mapper;

    @Override
    public Response toResponse(RuntimeException exception) {
        RestError error =RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setReasonMessage("A an unexpected / uncaught exception occurred");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();
        error.setDebugMessage(stackTrace);

        if (Log.isEnabled(Logger.Level.ERROR)) {
            Log.errorf("An unexpected / uncaught exception occurred: %s", error.toString());
        }

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
    }
}

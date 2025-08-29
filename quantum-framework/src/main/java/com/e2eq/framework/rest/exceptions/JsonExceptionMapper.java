package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.PrintWriter;
import java.io.StringWriter;

@Provider
public class JsonExceptionMapper implements ExceptionMapper<Exception> {
    @Override
    public Response toResponse(Exception exception) {
        RestError error =RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        error.setReasonMessage("A an unexpected / uncaught exception occurred");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();
        error.setDebugMessage(stackTrace);

        exception.printStackTrace(); // or log it

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();

    }
}
package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.PrintWriter;
import java.io.StringWriter;

@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {
    @Override
    public Response toResponse(NotFoundException exception) {
        RestError error =RestError.builder().build();
        error.setStatusMessage(exception.getMessage());
        error.setStatus(Response.Status.NOT_FOUND.getStatusCode());
        error.setReasonMessage(String.format("The URL: %s was not found msg:", exception.getMessage()));
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();
        error.setDebugMessage(stackTrace);

        return Response.status(Response.Status.NOT_FOUND).entity(error).build();
    }
}

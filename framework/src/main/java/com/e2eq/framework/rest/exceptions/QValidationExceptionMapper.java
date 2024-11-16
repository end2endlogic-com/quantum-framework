package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.exceptions.E2eqValidationException;

import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.PrintWriter;
import java.io.StringWriter;

@Provider
public class QValidationExceptionMapper implements ExceptionMapper<E2eqValidationException> {

    @Override
    public Response toResponse(E2eqValidationException e) {
        RestError error =RestError.builder().build();
        error.setStatusMessage(e.getMessage());
        error.setStatus(Response.Status.BAD_REQUEST.getStatusCode());
        error.setReasonMessage("A validation exception occurred");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String stackTrace = sw.toString();

        error.setDebugMessage(stackTrace);

        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}

package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.exceptions.E2eqValidationException;

import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;

@Provider
public class QValidationExceptionMapper implements ExceptionMapper<E2eqValidationException> {

    @Override
    public Response toResponse(E2eqValidationException exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();

        Set<String> violations = exception.getViolationSet().stream()
                .map(violation -> violation.getPropertyPath().toString() + " " + violation.getMessage())
                .collect(java.util.stream.Collectors.toSet());

        RestError error = RestError.builder()
                .statusMessage("The request failed due to validation errors")
                .status(Response.Status.BAD_REQUEST.getStatusCode())
                .reasonMessage(exception.getMessage())
                .debugMessage(stackTrace)
                .constraintViolations(violations)
                .build();

        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}

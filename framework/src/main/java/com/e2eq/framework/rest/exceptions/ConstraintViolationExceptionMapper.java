package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import jakarta.validation.ConstraintViolationException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;

@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper <ConstraintViolationException>{

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stackTrace = sw.toString();

        Set<String> violations = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath().toString() + " " + violation.getMessage())
                .collect(java.util.stream.Collectors.toSet());

       RestError error = RestError.builder()
               .statusMessage("The request failed due to validation errors, correct the errors and try again.")
               .status(Response.Status.BAD_REQUEST.getStatusCode())
               .reasonMessage("A validation exception occurred")
               .debugMessage(stackTrace)
               .constraintViolations(violations)
               .build();

        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}

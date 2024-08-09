package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.exceptions.E2eqValidationException;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class QExceptionMapper implements ExceptionMapper<E2eqValidationException> {

    @Override
    public Response toResponse(E2eqValidationException e) {
        return Response.status(Response.Status.FORBIDDEN).entity(e.toString()).build();
    }
}

package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.rest.models.RestError;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Typed refusals of application binding and install writes, with their code as {@code errorCode}. */
@Provider
public class ApplicationAccessExceptionMapper implements ExceptionMapper<ApplicationAccessException> {

    @Override
    public Response toResponse(ApplicationAccessException exception) {
        Response.Status status = Response.Status.fromStatusCode(exception.code().httpStatus());
        RestError error = RestError.builder()
            .status(status.getStatusCode())
            .statusMessage(status.getReasonPhrase())
            .errorCode(exception.code().name())
            .reasonMessage(exception.publicMessage())
            .build();
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(error)
            .build();
    }
}

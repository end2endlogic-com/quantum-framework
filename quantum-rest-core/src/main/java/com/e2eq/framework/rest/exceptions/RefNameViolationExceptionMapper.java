package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.exceptions.RefNameViolationException;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * Maps a canonical refName violation to a typed client error: 409 when an update tries to change an
 * existing refName, 400 when a new refName breaks the rule. {@code errorCode} carries the
 * {@link RefNameViolationException.Code} and {@code diagnostics} the offending values.
 */
@Provider
public class RefNameViolationExceptionMapper implements ExceptionMapper<RefNameViolationException> {

    @Override
    public Response toResponse(RefNameViolationException exception) {
        ExceptionLoggingUtils.logWarn(exception, "refName violation");

        Response.Status status = exception.getCode() == RefNameViolationException.Code.REFNAME_IMMUTABLE
                ? Response.Status.CONFLICT
                : Response.Status.BAD_REQUEST;

        RestError error = RestError.builder()
                .status(status.getStatusCode())
                .statusMessage("The refName is not allowed")
                .reasonMessage(exception.getMessage())
                .errorCode(exception.getCode().name())
                .diagnostics(exception.diagnostics())
                .constraintViolations(Set.of("refName " + exception.getCode().name()))
                .build();

        return Response.status(status).entity(error).build();
    }
}

package com.e2eq.framework.rest.resources;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.service.access.AccessInviteException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class AccessInviteExceptionMapper implements ExceptionMapper<AccessInviteException> {

    @Override
    public Response toResponse(AccessInviteException exception) {
        Response.Status status = statusFor(exception.getCode());
        RestError error = RestError.builder()
            .status(status.getStatusCode())
            .statusMessage(status.getReasonPhrase())
            .errorCode(exception.getCode().name())
            .reasonMessage(exception.getMessage())
            .build();
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON_TYPE)
            .entity(error)
            .build();
    }

    static Response.Status statusFor(AccessInviteException.Code code) {
        return switch (code) {
            case INVALID_REQUEST, INVITE_TOKEN_INVALID -> Response.Status.BAD_REQUEST;
            case INVITE_EXPIRED -> Response.Status.GONE;
            case INVITE_NOT_PENDING -> Response.Status.CONFLICT;
            case INVITE_IDENTITY_MISMATCH, INVITE_NOT_AUTHORIZED, INVITE_ROLE_NOT_GRANTABLE -> Response.Status.FORBIDDEN;
            case INVITE_SIGN_IN_REQUIRED -> Response.Status.UNAUTHORIZED;
            case INVITE_NOT_FOUND -> Response.Status.NOT_FOUND;
            case INVITE_PROVISIONING_FAILED -> Response.Status.INTERNAL_SERVER_ERROR;
        };
    }
}

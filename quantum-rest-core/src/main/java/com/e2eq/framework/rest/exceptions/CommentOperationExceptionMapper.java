package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.collaboration.CommentOperationException;
import com.e2eq.framework.rest.models.CommentApiError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CommentOperationExceptionMapper implements ExceptionMapper<CommentOperationException> {

    @Override
    public Response toResponse(CommentOperationException exception) {
        int status = switch (exception.getCode()) {
            case AUTHENTICATED_ACTOR_REQUIRED -> Response.Status.UNAUTHORIZED.getStatusCode();
            case CHAIN_NOT_FOUND, COMMENT_NOT_FOUND -> Response.Status.NOT_FOUND.getStatusCode();
            case CHAIN_LOCKED -> Response.Status.CONFLICT.getStatusCode();
            case INVALID_ID, INVALID_REQUEST -> Response.Status.BAD_REQUEST.getStatusCode();
        };
        return Response.status(status)
                .entity(new CommentApiError(exception.getCode().name(), exception.getMessage(), status))
                .build();
    }
}

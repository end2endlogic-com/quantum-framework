package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.persistent.morphia.CommentHierarchyException;
import com.e2eq.framework.rest.models.CommentApiError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CommentHierarchyExceptionMapper implements ExceptionMapper<CommentHierarchyException> {

    @Override
    public Response toResponse(CommentHierarchyException exception) {
        int status = switch (exception.getCode()) {
            case CHAIN_NOT_FOUND, PARENT_NOT_FOUND, MEDIA_REFERENCE_NOT_FOUND ->
                    Response.Status.NOT_FOUND.getStatusCode();
            case IMMUTABLE_AUTHOR, IMMUTABLE_HIERARCHY, IMMUTABLE_REQUEST_ID ->
                    Response.Status.CONFLICT.getStatusCode();
            case CHAIN_REQUIRED, PARENT_CHAIN_MISMATCH, MAX_DEPTH_EXCEEDED, MEDIA_REFERENCE_INVALID ->
                    Response.Status.BAD_REQUEST.getStatusCode();
        };
        return Response.status(status)
                .entity(new CommentApiError(exception.getCode().name(), exception.getMessage(), status))
                .build();
    }
}

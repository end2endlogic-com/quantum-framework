package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.persistent.morphia.MediaReferenceOperationException;
import com.e2eq.framework.rest.models.MediaApiError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class MediaReferenceOperationExceptionMapper
        implements ExceptionMapper<MediaReferenceOperationException> {

    @Override
    public Response toResponse(MediaReferenceOperationException exception) {
        int status = switch (exception.getCode()) {
            case CREATOR_REQUIRED -> Response.Status.BAD_REQUEST.getStatusCode();
            case IMMUTABLE_CREATOR, IMMUTABLE_STORAGE_IDENTITY ->
                    Response.Status.CONFLICT.getStatusCode();
        };
        return Response.status(status)
                .entity(new MediaApiError(exception.getCode().name(), exception.getMessage(), status))
                .build();
    }
}

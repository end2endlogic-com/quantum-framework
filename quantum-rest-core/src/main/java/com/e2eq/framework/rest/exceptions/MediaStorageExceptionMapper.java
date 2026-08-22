package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.media.MediaStorageException;
import com.e2eq.framework.rest.models.MediaApiError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class MediaStorageExceptionMapper implements ExceptionMapper<MediaStorageException> {

    @Override
    public Response toResponse(MediaStorageException exception) {
        int status = switch (exception.getCode()) {
            case INVALID_REFERENCE -> Response.Status.BAD_REQUEST.getStatusCode();
            case OBJECT_NOT_FOUND -> Response.Status.NOT_FOUND.getStatusCode();
            case ACCESS_DENIED -> Response.Status.FORBIDDEN.getStatusCode();
            case STORAGE_UNAVAILABLE -> Response.Status.SERVICE_UNAVAILABLE.getStatusCode();
            case SIGNING_FAILED, DELETE_FAILED -> Response.Status.BAD_GATEWAY.getStatusCode();
        };
        return Response.status(status)
                .entity(new MediaApiError(exception.getCode().name(), exception.getMessage(), status))
                .build();
    }
}

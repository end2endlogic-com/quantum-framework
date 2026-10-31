package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.tasks.WorkItemOperationException;
import com.e2eq.framework.rest.models.WorkItemError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class WorkItemOperationExceptionMapper implements ExceptionMapper<WorkItemOperationException> {
    @Override
    public Response toResponse(WorkItemOperationException exception) {
        Response.Status status = switch (exception.getCode()) {
            case INVALID_REQUEST -> Response.Status.BAD_REQUEST;
            case NOT_FOUND -> Response.Status.NOT_FOUND;
            case NOT_ELIGIBLE -> Response.Status.FORBIDDEN;
            case NOT_ASSIGNED, LEASE_EXPIRED, INVALID_TRANSITION, REVISION_CONFLICT -> Response.Status.CONFLICT;
            case INTERNAL_ERROR -> Response.Status.INTERNAL_SERVER_ERROR;
        };
        return Response.status(status)
                .entity(new WorkItemError(exception.getCode(), exception.getMessage()))
                .build();
    }
}

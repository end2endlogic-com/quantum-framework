package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class DataDomainGovernanceExceptionMapper implements ExceptionMapper<DataDomainGovernanceException> {

    @Override
    public Response toResponse(DataDomainGovernanceException exception) {
        Response.Status status = switch (exception.getCode()) {
            case DATA_DOMAIN_NOT_PERMITTED, DATA_DOMAIN_NOT_UPDATABLE -> Response.Status.FORBIDDEN;
            case ENTITY_NOT_IN_SCOPE -> Response.Status.NOT_FOUND;
            case SECURITY_CONTEXT_MISSING -> Response.Status.INTERNAL_SERVER_ERROR;
        };
        if (status == Response.Status.INTERNAL_SERVER_ERROR) {
            Log.errorf("Refusing ungoverned REST write: %s", exception.getMessage());
        } else {
            Log.warnf("Rejected REST write (%s): %s", exception.getCode(), exception.getMessage());
        }
        RestError error = RestError.builder()
                .status(status.getStatusCode())
                .statusMessage(exception.getCode().name())
                .reasonMessage(exception.getMessage())
                .build();
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(error).build();
    }
}

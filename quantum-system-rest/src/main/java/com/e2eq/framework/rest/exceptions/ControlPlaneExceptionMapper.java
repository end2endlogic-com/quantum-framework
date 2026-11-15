package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.system.remote.ControlPlaneException;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A failure at the remote directory owner (the control plane), on a read or a write. The owner's
 * refusal keeps its status: a 4xx from the owner is answered with that 4xx. Anything else (owner
 * unreachable, owner 5xx, a reply that breaks the contract, no endpoint configured) is a 502 Bad
 * Gateway, so the caller can tell the owner failed rather than this service. Nothing is retried
 * against a local copy.
 */
@Provider
public class ControlPlaneExceptionMapper implements ExceptionMapper<ControlPlaneException> {

    @Override
    public Response toResponse(ControlPlaneException exception) {
        int owner = exception.httpStatus().orElse(0);
        int status = owner >= 400 && owner < 500 ? owner : Response.Status.BAD_GATEWAY.getStatusCode();
        ExceptionLoggingUtils.logDebug(exception, "Directory owner call failed");
        return Response.status(status)
            .entity(RestError.builder()
                .status(status)
                .statusMessage("The directory owner did not complete " + exception.operation() + ": "
                    + exception.getMessage())
                .reasonMessage(exception.kind().name())
                .build())
            .build();
    }
}

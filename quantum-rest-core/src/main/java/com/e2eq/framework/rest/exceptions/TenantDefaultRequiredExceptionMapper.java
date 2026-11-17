package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo.TenantDefaultRequiredException;
import com.e2eq.framework.rest.models.RestError;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A realm grant refused because it would reach several tenants of a shared realm without a
 * default tenant ({@code UserRealmRoleRepo}) is a client error the caller fixes by setting the
 * identity's default first: 409 with code {@code TENANT_DEFAULT_REQUIRED}, never a 500. Every
 * REST surface that saves grants (user realm roles, invites, provisioning, the control plane)
 * reaches it through the repository.
 */
@Provider
public class TenantDefaultRequiredExceptionMapper implements ExceptionMapper<TenantDefaultRequiredException> {

    @Override
    public Response toResponse(TenantDefaultRequiredException exception) {
        Log.warnf("Rejected realm grant (%s): %s", TenantDefaultRequiredException.CODE, exception.getMessage());
        RestError error = RestError.builder()
                .status(Response.Status.CONFLICT.getStatusCode())
                .statusMessage(TenantDefaultRequiredException.CODE)
                .reasonMessage(exception.getMessage())
                .build();
        return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON).entity(error).build();
    }
}

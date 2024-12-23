package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.Counter;
import com.e2eq.framework.rest.models.CounterResponse;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.persistent.morphia.CounterRepo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/integration/counters")
@RolesAllowed({ "user", "admin" })
@Tag(name = "integration", description = "Operations related integrating into the system")
public class CounterResource extends BaseResource<Counter, CounterRepo> {

    protected CounterResource(CounterRepo repo) {
        super(repo);
    }

    @Path("/increment")
    @GET
    public Response incrementCounter(@QueryParam(value = "counterName") String counterName) {
        if (SecurityContext.getPrincipalContext().isPresent()) {
            DataDomain dd = SecurityContext.getPrincipalDataDomain().get();
            long value = repo.getAndIncrement(counterName, dd, 1l);
            CounterResponse response = new CounterResponse();
            response.setValue(value);
            return Response.ok(response).build();
        }
        else {
            RestError error = RestError.builder()

                    .status(Response.Status.UNAUTHORIZED.getStatusCode())
                    .reasonCode(Response.Status.UNAUTHORIZED.getStatusCode())
                    .debugMessage(Response.Status.UNAUTHORIZED.getReasonPhrase())
                    .statusMessage("User is not authenticated but this call requires authentication").build();
            return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
        }
    }
}

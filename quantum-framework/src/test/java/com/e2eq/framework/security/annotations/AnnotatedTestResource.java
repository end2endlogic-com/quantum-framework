package com.e2eq.framework.security.annotations;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@FunctionalMapping(area = "sales", domain = "order")
@Path("/annotated")
public class AnnotatedTestResource {

    @GET
    @Path("/view")
    @Produces(MediaType.TEXT_PLAIN)
    @FunctionalAction("VIEW")
    public String view() {
        ResourceContext rc = SecurityContext.getResourceContext().orElse(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
        return rc.getArea() + ":" + rc.getFunctionalDomain() + ":" + rc.getAction();
    }

    @POST
    @Path("/create")
    @Produces(MediaType.TEXT_PLAIN)
    public String create() {
        // No FunctionalAction annotation: expect SecurityFilter to infer from HTTP method (POST -> CREATE)
        ResourceContext rc = SecurityContext.getResourceContext().orElse(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
        return rc.getArea() + ":" + rc.getFunctionalDomain() + ":" + rc.getAction();
    }
}

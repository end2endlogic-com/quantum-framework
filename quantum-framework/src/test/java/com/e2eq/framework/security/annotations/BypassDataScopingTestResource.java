package com.e2eq.framework.security.annotations;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/**
 * Test resource for verifying bypassDataScoping attribute behavior.
 * This simulates system-level operations that don't operate on tenant-scoped data.
 */
@FunctionalMapping(area = "system", domain = "admin")
@Path("/annotated-bypass")
@RolesAllowed("admin")
public class BypassDataScopingTestResource {

    /**
     * Endpoint with bypassDataScoping = true (system-level operation).
     * Should succeed even when SCOPED constraints are present.
     */
    @POST
    @Path("/system-operation")
    @Produces(MediaType.TEXT_PLAIN)
    @FunctionalAction(value = "SYSTEM_OP", bypassDataScoping = true)
    public String systemOperation() {
        ResourceContext rc = SecurityContext.getResourceContext().orElse(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
        return "bypass:" + rc.getArea() + ":" + rc.getFunctionalDomain() + ":" + rc.getAction();
    }

    /**
     * Endpoint without bypassDataScoping (default = false).
     * SCOPED constraints should be enforced at data layer.
     */
    @POST
    @Path("/normal-operation")
    @Produces(MediaType.TEXT_PLAIN)
    @FunctionalAction("NORMAL_OP")
    public String normalOperation() {
        ResourceContext rc = SecurityContext.getResourceContext().orElse(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
        return "normal:" + rc.getArea() + ":" + rc.getFunctionalDomain() + ":" + rc.getAction();
    }

    /**
     * Endpoint without any FunctionalAction annotation.
     * bypassDataScoping defaults to false.
     */
    @GET
    @Path("/inferred-action")
    @Produces(MediaType.TEXT_PLAIN)
    public String inferredAction() {
        ResourceContext rc = SecurityContext.getResourceContext().orElse(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);
        return "inferred:" + rc.getArea() + ":" + rc.getFunctionalDomain() + ":" + rc.getAction();
    }
}


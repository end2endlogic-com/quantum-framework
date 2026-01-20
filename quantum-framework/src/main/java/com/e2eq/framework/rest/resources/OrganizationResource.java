package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.OrganizationRepo;
import com.e2eq.framework.model.security.Organization;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/security/accounts/organizations")
@RolesAllowed({"admin", "system"})
@Tag(name = "security", description = "Operations related to security")
public class OrganizationResource extends BaseResource<Organization, OrganizationRepo>  {
    protected OrganizationResource(OrganizationRepo repo) {
        super(repo);
    }
}

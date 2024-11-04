package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.OrganizationRepo;
import com.e2eq.framework.model.persistent.security.Organization;
import jakarta.ws.rs.Path;

@Path("/security/accounts/organizations")
public class OrganizationResource extends BaseResource<Organization, OrganizationRepo>  {
    protected OrganizationResource(OrganizationRepo repo) {
        super(repo);
    }
}

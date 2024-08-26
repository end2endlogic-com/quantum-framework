package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.BaseRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.persistent.security.FunctionalDomain;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;


@Path("/security/functionalDomain")
@RolesAllowed("admin")
public class FunctionalDomainResource extends BaseResource<FunctionalDomain, BaseRepo<FunctionalDomain>>{
   protected FunctionalDomainResource (FunctionalDomainRepo repo) {
      super(repo);
   }
}

package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.persistent.security.FunctionalDomain;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;


@Path("/security/functionalDomain")
@RolesAllowed("admin")
@Tag(name = "security", description = "Operations related to managing the security of the system")
public class FunctionalDomainResource extends BaseResource<FunctionalDomain, BaseMorphiaRepo<FunctionalDomain>>{
   protected FunctionalDomainResource (FunctionalDomainRepo repo) {
      super(repo);
   }
}

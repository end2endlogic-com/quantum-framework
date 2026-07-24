package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.rest.core.BaseResource;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/security/application")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@FunctionalMapping(area = "security", domain = "application")
public class ApplicationResource extends BaseResource<Application, ApplicationRepo> {
   protected ApplicationResource (ApplicationRepo repo) {
      super(repo);
   }
}

package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.security.Policy;
import jakarta.ws.rs.Path;

@Path("/security/permission/policies")
public class PolicyResource extends BaseResource<Policy, PolicyRepo>{
   protected PolicyResource (PolicyRepo repo) {
      super(repo);
   }
}

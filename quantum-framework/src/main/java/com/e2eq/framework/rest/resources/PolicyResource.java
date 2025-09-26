package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.securityrules.RuleContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/security/permission/policies")
public class PolicyResource extends BaseResource<Policy, PolicyRepo>{
   @Inject
   RuleContext ruleContext;

   protected PolicyResource (PolicyRepo repo) {
      super(repo);
   }

   @POST
   @Path("/refreshRuleContext")
   public Response refreshRuleContext(@HeaderParam("X-Realm") String realm) {
      String effectiveRealm = (realm == null || realm.isBlank()) ? ruleContext.getDefaultRealm() : realm;
      ruleContext.reloadFromRepo(effectiveRealm);
      return Response.ok().build();
   }
}

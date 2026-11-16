package com.e2eq.framework.rest.filters.inactive;

import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;

import java.io.IOException;
//TODO refactor this to utilize url vs. annotation and look to add pcontext with out a permission annotation

//@Provider
//@PermissionCheck
public class PermissionPreFilter implements ContainerRequestFilter, ContainerResponseFilter {

   RuleContext ruleContext;


   JWTParser parser;

   String area;
   String functionalDomain;
   String action;

   public PermissionPreFilter(@NotNull RuleContext ruleContext,
                              @NotNull JWTParser parser,
                              @NotNull String area,
                              @NotNull String functionalDomain,
                              @NotNull String action) {
      this.ruleContext = ruleContext;
      this.parser = parser;
      this.area = area;
      this.functionalDomain = functionalDomain;
      this.action = action;
   }

   /**
    * Sets the resource context ({@code area/functionalDomain/action}) that {@code @PermissionCheck}
    * declares. It reads nothing from the bearer token: the principal is resolved by
    * {@code SecurityFilter} (identity-only tokens, {@code IDENTITY_ONLY_TOKENS_DESIGN.md}). The
    * principal this filter used to build from {@code groups}/{@code tenantId}/{@code orgRefName}
    * claims was never used (its rule check was commented out), and Quarkus verifies
    * the token's signature (proactively, or when SecurityFilter reads the identity).
    */
   @Override
   public void filter (ContainerRequestContext requestContext) throws IOException {
      ResourceContext rcontext = new ResourceContext.Builder()
              .withArea(area)
              .withFunctionalDomain(functionalDomain)
              .withAction(action)
              .build();

      SecurityContext.setResourceContext(rcontext);
   }

   @Override
   public void filter (ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
         SecurityContext.clearResourceContext();
   }
}

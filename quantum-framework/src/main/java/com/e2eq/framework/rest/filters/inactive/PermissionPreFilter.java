package com.e2eq.framework.rest.filters.inactive;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
//TODO refactor this to utilize url vs. annotation and look to add pcontext with out a permission annotation

//@Provider
//@PermissionCheck
public class PermissionPreFilter implements ContainerRequestFilter, ContainerResponseFilter {

   private static final String AUTHENTICATION_SCHEME = "Bearer";


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

   @Override
   public void filter (ContainerRequestContext requestContext) throws IOException {


      // Parse out the area and functionalDomain
      // based upon the URL convention of
      // /{area}/{functionalDomain}/action
      ResourceContext rcontext = new ResourceContext.Builder()
              .withArea(area)
              .withFunctionalDomain(functionalDomain)
              .withAction(action)
              .build();

      SecurityContext.setResourceContext(rcontext);

      // Get the Authorization header from the request
      String authorizationHeader =
         requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

      // Extract the token from the Authorization header if one is presented
      if (authorizationHeader != null) {
         String token = authorizationHeader
            .substring(AUTHENTICATION_SCHEME.length()).trim();

         String tenantId;
         String accountId;
         String defaultRealm;
         String principal;
         String orgRefName;
         String scope;
         String[] roles = new String[0];

         try {
            // The parser will check the public key and signature of the token
            JsonWebToken jwt = parser.parse(token);
            orgRefName = jwt.getClaim("orgRefName");
            tenantId = jwt.getClaim("tenantId");
            accountId = jwt.getClaim("accountId");
            defaultRealm = jwt.getClaim("defaultRealm");
            roles = jwt.getGroups().toArray(roles);
            scope = jwt.getClaim("scope");

            DataDomain dataDomain = new DataDomain();
            dataDomain.setOrgRefName(orgRefName);
            dataDomain.setAccountNum(accountId);
            dataDomain.setTenantId(tenantId);
            dataDomain.setDataSegment(0);


            principal = requestContext.getSecurityContext().getUserPrincipal().getName();
            PrincipalContext pcontext = new PrincipalContext.Builder()
               .withDataDomain(dataDomain)
               .withDefaultRealm(defaultRealm)
               .withUserId(principal)
               .withRoles(roles)
               .withScope(scope)
               .build();


           /* SecurityCheckResponse sresp = ruleContext.check(pcontext, rcontext);
            if (sresp.getFinalEffect() != RuleEffect.ALLOW) {
               requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).entity(sresp).build());
            } */

         } catch (ParseException e) {
            e.printStackTrace();
            requestContext.abortWith(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build());
         }

      }
      // Just let it pass through as a unauthenticated request
      //else {
         //
         //  ((ContainerRequestContextImpl) requestContext).quarkusRestContext.getMethodAnnotations().length
         //ContainerRequestContextImpl crequestContext = ((ContainerRequestContextImpl) requestContext);
         //crequestContext.quarkusRestContext.getMethodAnnotations();
        //}
   }

   @Override
   public void filter (ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
         SecurityContext.clearResourceContext();
   }
}

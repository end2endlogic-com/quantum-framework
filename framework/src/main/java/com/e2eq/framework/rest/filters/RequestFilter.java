package com.e2eq.framework.rest.filters;


import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.PrincipalContext;
import com.e2eq.framework.model.security.ResourceContext;
import com.e2eq.framework.model.security.SecurityContext;
import com.e2eq.framework.model.security.rules.RuleContext;
import com.e2eq.framework.security.model.persistent.models.security.CredentialUserIdPassword;
import com.e2eq.framework.security.model.persistent.models.security.Realm;
import com.e2eq.framework.security.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.security.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import java.util.StringTokenizer;

//@Provider
//@PreMatching
//@PermissionCheck
public class RequestFilter implements ContainerRequestFilter {

   private static final String AUTHENTICATION_SCHEME = "Bearer";
   @Inject
   JWTParser parser;

   @Inject
   RuleContext ruleContext;

   @Inject
   RealmRepo realmRepo;

   @Inject
   CredentialRepo credentialRepo;

   @Override
   public void filter (ContainerRequestContext requestContext) throws IOException {

      Log.info("In Request  filter:");
      ResourceContext resourceContext = determineResourceContext(requestContext);
      PrincipalContext principalContext = determinePrincipalContext(requestContext);
      if (principalContext == null) {
         throw new IllegalStateException("Principal context came back null and should not be null");
      }

      requestContext.setSecurityContext(new jakarta.ws.rs.core.SecurityContext() {
         @Override
         public Principal getUserPrincipal() {
            return new Principal() {
               @Override
               public String getName() {
                  return principalContext.getUserId();
               }
            };
         }

         @Override
         public boolean isUserInRole(String r) {
            if (r.equals("user")) {
               return true;
            }

          for (String role : principalContext.getRoles()) {
             if (role.equals(r)) {
                return true;
             }
          }
            return false;
         }

         @Override
         public boolean isSecure() {
            return false;
         }

         @Override
         public String getAuthenticationScheme() {
            return "basic";
         }
      });
   }

   /*
        // Check if the method is annotated.
         Method method = ((ContainerRequestContextImpl) requestContext).getServerRequestContext().getResteasyReactiveResourceInfo().getMethod();

          RestError error = new RestError();
          error.setStatus(Response.Status.FORBIDDEN.getStatusCode());
          error.setStatusMessage("User is not logged in, attempt to take non-public action, forbidden, present an auth token to access this method");
          error.setDebugMessage(" Attempt to access unannotated method:" + method.getName() + " with out presenting an auth token. ");
          error.setReasonMessage(" Either the method must be annotated to allow public access, or user must provide auth token, neither is the case");
          requestContext.abortWith(Response.status(Response.Status.FORBIDDEN).entity(error).build());
    */


   protected ResourceContext determineResourceContext(ContainerRequestContext requestContext) {
      Log.info("determining Resource Context");

      ResourceContext rcontext = null;


      /**
       Determine the resource context from the path
       */
      String path = requestContext.getUriInfo().getPath();
      StringTokenizer tokenizer = new StringTokenizer(path, "/");

      String area;
      String functionalDomain;
      String action;
      int tokenCount = tokenizer.countTokens();

      if (tokenCount > 2) {
         if (Log.isEnabled(Logger.Level.WARN)) {
            Log.warn("Path: +" + path + " Request format is such that there are more than two levels to parse from parsing...");
         }
         area = tokenizer.nextToken();
         functionalDomain = tokenizer.nextToken();
         action = tokenizer.nextToken();

         if (Log.isDebugEnabled()) {
            Log.debug("Based upon request convention assumed that the area is:" + area + " functional domain is:" + functionalDomain + " and action is:" + action);
         }

         rcontext = new ResourceContext.Builder()
                                       .withAction(action)
                                       .withArea(area)
                                       .withFunctionalDomain(functionalDomain)
                                       .build();
         SecurityContext.setResourceContext(rcontext);
         if (Log.isDebugEnabled())
            Log.debug("Resource Context set");

      } else if (tokenCount == 2) {
         if (Log.isEnabled(Logger.Level.INFO)) {
            Log.infof("Path:%s has two tokens", path);
         }

         functionalDomain = tokenizer.nextToken();
         action = tokenizer.nextToken();
         rcontext = new ResourceContext.Builder()
                                       .withAction(action)
                                       .withArea(functionalDomain)
                                       .withFunctionalDomain(functionalDomain)
                                       .build();
         SecurityContext.setResourceContext(rcontext);
         if (Log.isEnabled(Logger.Level.WARN)) {
            Log.warn(path + ":Odd request convention, not following /area/fd/fa .. so assuming the fd and area are equal: " +
                        "Only two tokens for resource, assuming area as fd, fd=" + functionalDomain + " action=" + action);
         }

         if (Log.isDebugEnabled()) {
            Log.debug("Resource Context set");
         }

      } else {
         Log.warn("Non conformant url:" + path + " could not set resource context as a result, expecting /area/functionalDomain/action: TokenCount:" + tokenCount);
         Log.warn("Creating generic Context");
         rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
      }

      return rcontext;

   }

   protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
      PrincipalContext pcontext=null;

      // Get the Authorization header from the request
      String authorizationHeader =
         requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

      if (authorizationHeader != null) {
         if (Log.isDebugEnabled()) {
            Log.debugf("Authorization Header Provided: %s", authorizationHeader);
         }

         String token = authorizationHeader
                           .substring(AUTHENTICATION_SCHEME.length()).trim();

         String orgRefName;
         String tenantId;
         String accountId;
         String defaultRealm;
         String principal;
         String scope;

         String[] roles = new String[0];
         try {
            JsonWebToken jwt = parser.parse(token);

            orgRefName = jwt.getClaim("orgRefName");
            tenantId = jwt.getClaim("tenantId");
            accountId = jwt.getClaim("accountId");
            defaultRealm = jwt.getClaim("defaultRealm");
            roles = jwt.getGroups().toArray(roles);
            scope = jwt.getClaim("scope");

            if (Log.isDebugEnabled()) {
               Log.debug("--- Request has an Existing JWT Token: --");
               Log.debug("UserId:" + jwt.getSubject());
               Log.debug("Org:" + orgRefName);
               Log.debug("tenant:" + tenantId);
               Log.debug("accountId:" + accountId);
               Log.debug("defaultRealm:" + defaultRealm);
               Log.debugf("roles:");
               for (String s : roles ) {
                  Log.debugf("   %s", s);
               }
               Log.debug("scope:" + scope);
            }
            principal = requestContext.getSecurityContext().getUserPrincipal().getName();

            DataDomain dataDomain = new DataDomain();
            dataDomain.setOrgRefName(orgRefName);
            dataDomain.setAccountNum(accountId);
            dataDomain.setTenantId(tenantId);
            dataDomain.setDataSegment(0);

            pcontext = new PrincipalContext.Builder()
                                           .withDefaultRealm(defaultRealm)
                                           .withDataDomain(dataDomain)
                                           .withUserId(principal)
                                           .withRoles(roles)
                                           .withScope(scope)
                                           .build();
         } catch (ParseException e) {
            e.printStackTrace();
            requestContext.abortWith(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("Token:" + token + "Msg:" + e.getMessage()).build());
         }
      } else {
         // No Authheader found
         if (Log.isEnabled(Logger.Level.WARN))
            Log.warn("No Authorization header presented ");

         // Lets see if there is a tenantId:
         String tenantId = requestContext.getUriInfo().getQueryParameters().getFirst("tenantId");
         if (tenantId != null) {
            if (Log.isInfoEnabled()) {
               Log.infof("tenantId Provided: %s", tenantId);
            }

            Optional<Realm> orealm = realmRepo.findByTenantId(tenantId);

            if (orealm.isPresent()) {
               Realm realm = orealm.get();
               DataDomain dataDomain = new DataDomain();
               dataDomain.setOrgRefName(realm.getOrgRefName());
               dataDomain.setAccountNum(realm.getAccountNumber());
               dataDomain.setTenantId(realm.getTenantId());
               dataDomain.setOwnerId(realm.getDefaultAdminUserId());

               Optional<CredentialUserIdPassword> ocreds = credentialRepo.findByUserId(tenantId, realm.getDefaultAdminUserId());
               String roles[] = null;
               if (ocreds.isPresent()) {
                  CredentialUserIdPassword cred = ocreds.get();
                  roles = cred.getRoles();

                  pcontext = new PrincipalContext.Builder()
                                .withDefaultRealm(realm.getRefName())
                                .withDataDomain(dataDomain)
                                .withUserId(realm.getDefaultAdminUserId())
                                .withRoles(roles)
                                .withScope("systemGenerated")
                                .build();

               } else {
                  throw new IllegalStateException("Default userId:" + realm.getDefaultAdminUserId() + " could not be found for tenantId:" + tenantId);
               }
            } else {
               // realm could not be found so we should punt
               throw new NotFoundException("Realm for tenantId:" + tenantId + " not found");
            }
         } else {
            if (Log.isEnabled(Logger.Level.WARN)) {
               Log.warn("No tenantId or auth token provided assuming an anonymous user context");
            }
            // so no auth token, no tenantId passed, no way to figure out tenant so default to system, and set an
            // anonymous user context
            String[] roles = {"ANONYMOUS"};
            pcontext = new PrincipalContext.Builder()
                          .withDefaultRealm(SecurityUtils.systemRealm)
                          .withDataDomain(SecurityUtils.systemDataDomain)
                          .withUserId(SecurityUtils.anonymousUserId)
                          .withRoles(roles)
                          .withScope("systemGenerated")
                          .build();
         }

      }

      return pcontext;
   }


}

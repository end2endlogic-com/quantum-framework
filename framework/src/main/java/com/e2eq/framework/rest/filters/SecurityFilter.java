package com.e2eq.framework.rest.filters;


import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.security.Principal;
import java.util.Optional;
import java.util.StringTokenizer;



@Provider
//@PreMatching
//@PermissionCheck
public class SecurityFilter implements ContainerRequestFilter {

   private static final String AUTHENTICATION_SCHEME = "Bearer";

   @ConfigProperty(name = "auth.provider")
   String authProvider;

   @Inject
   JWTParser parser;

   @Inject
   RuleContext ruleContext;

   @Inject
   RealmRepo realmRepo;

   @Inject
   CredentialRepo credentialRepo;

   @Inject
   JsonWebToken jwt;

   @Inject
   UserProfileRepo userProfileRepo;

   private ContainerRequestContext requestContext;

   @Override
   public void filter (ContainerRequestContext requestContext) throws IOException {
      // the context may be set in an upstream filter specifically in the PermissionPreFilter based upon
      // an annotation.  In this case determineResourceContext() as well determinePrincipalContext() should
      // consider if the context is already set and re use it.  If this causes issues with an old context being
      // left around, determine how to clear the context after the action is complete, vs, upfront here.

      //SecurityContext.clear();


      this.requestContext = requestContext;
      ResourceContext resourceContext = determineResourceContext(requestContext);
      PrincipalContext principalContext = determinePrincipalContext(requestContext);
      if (principalContext == null) {
         throw new IllegalStateException("Principal context came back null and should not be null");
      }

      SecurityContext.setPrincipalContext(principalContext);
      SecurityContext.setResourceContext(resourceContext);
      /*
      Should not be needed as it should be done upstream
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
          for (String role : principalContext.getRoles()) {
             if (role.equals(r)) {
                return true;
             }
          }
            return false;
         }

         @Override
         public boolean isSecure() {
            if (requestContext != null) {
               // Get the URI scheme from the request context
               String scheme = requestContext.getUriInfo().getRequestUri().getScheme();
               return "https".equalsIgnoreCase(scheme);
            }
            return false;
         }

         @Override
         public String getAuthenticationScheme() {
            return "basic";
         }
      }); */
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


      if (!SecurityContext.getResourceContext().isPresent()) {
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
               Log.warn("Path: +" + path + " three or more levels");
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
            rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
         }
         return rcontext;
      }
      else {
         return SecurityContext.getResourceContext().get();
      }
   }

   protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
      PrincipalContext pcontext=null;

      // Get the Authorization header from the request
      String authorizationHeader =
         requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

      if (authorizationHeader != null) {
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
           // principal = requestContext.getSecurityContext().getUserPrincipal().getName();
            // the same as  the following but more consistent as its from the token and we can't predict
            // whats happen upsstream of this.
            principal = jwt.getSubject();
            // so normally you might think this would work but since its based upon all the other
            // infratructure you caught in a catch 22 where this code needs the prinicpal context to form the rules
            // which don't exist yet, so in reality we probably need a different machanism
            Optional<UserProfile> ouserProfile = userProfileRepo.getByUserId(principal);
            if (ouserProfile.isPresent()) {
               UserProfile userProfile = ouserProfile.get();
               if (userProfile.getOrgRefName() != null)
                  orgRefName = userProfile.getOrgRefName();
               if (userProfile.getTenantId()!= null)
                  tenantId = userProfile.getTenantId();
               if (userProfile.getDefaultRealm()!= null)
                  defaultRealm = userProfile.getDefaultRealm();
               if (userProfile.getAccountNumber() != null )
                  accountId = userProfile.getAccountNumber();
            }

            if (orgRefName == null || tenantId == null || defaultRealm == null) {
               throw new IllegalStateException(String.format("Could not determine orgRefName, tenantId, or defaultRealm from token or user profile with principal:%s token: %s \n",principal, token));
            }

            DataDomain dataDomain = new DataDomain();
            dataDomain.setOrgRefName(orgRefName);
            dataDomain.setAccountNum(accountId);
            dataDomain.setTenantId(tenantId);
            dataDomain.setOwnerId(principal);
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
            Log.warnf("No Authorization header presented for request: %s", requestContext.getUriInfo().getRequestUri());

         // Lets see if there is a tenantId:
         String tenantId = requestContext.getUriInfo().getQueryParameters().getFirst("tenantId");
         if (tenantId != null) {
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
            Log.warnf("Setting roles on Principal context to ANONYMOUS");
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

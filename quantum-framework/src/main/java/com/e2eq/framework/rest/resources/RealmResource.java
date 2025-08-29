package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.filters.PermissionCheck;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.persistent.security.Realm;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;


@Path("/security/realm")
@RolesAllowed({ "admin" })
@Tag(name = "security", description = "Operations related to security")
public class RealmResource extends BaseResource<Realm, BaseMorphiaRepo<Realm>> {
   @Inject
   CredentialRepo credentialRepo;

   protected RealmResource (RealmRepo repo) {
      super(repo);
   }



   @Path("byTenantId")
   @GET
   @RolesAllowed({"user", "admin"})
   @PermissionCheck(
      area = "Security",
      functionalDomain="Realm",
      action="view"
   )
   @Produces({ "application/json"})
   @Consumes({"application/json"})
   public Response byTenantId(@QueryParam("tenantId") String tenantId) {
        RealmRepo rrepo = (RealmRepo) this.repo;
        Optional<Realm> orealm = rrepo.findByTenantId(tenantId, false);
        if (orealm.isPresent()) {
           return Response.ok(orealm.get()).build();
        } else {
           RestError error = RestError.builder()
                   .status(Response.Status.NOT_FOUND.getStatusCode())
                   .statusMessage("Could not find realm for tenantId:" + tenantId).build();
           return Response.status(Response.Status.NOT_FOUND).entity(error).build();
        }
   }

   @Path("possibleImpersonations/realms")
   @Produces({ "application/json"})
   @Consumes({"application/json"})
   @GET
   @RolesAllowed({"user", "admin"})
   public Response possibleImpersonationRealms() {
      PrincipalContext pContext;
      if (!SecurityContext.getPrincipalContext().isPresent()) {
         RestError error = RestError.builder()
                .status(Response.Status.PRECONDITION_FAILED .getStatusCode())
                .statusMessage("possibleImpersonations/realms requires an authenticated user, get the SecurityContext PrincipalContext is not present, this indicates a logic error in the server.").build();
         return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
      } else {
         pContext = SecurityContext.getPrincipalContext().get();
      }

      Optional<CredentialUserIdPassword> ocredential =credentialRepo.findByUserId(pContext.getUserId());
      List<Realm> realms;
      if (ocredential.isPresent()) {
         CredentialUserIdPassword credential = ocredential.get();
         String realmFilter = credential.getRealmRegEx();

         if (credential.getImpersonateFilterScript() != null && realmFilter != null) {
            realms = repo.getListByQuery(0,-1, realmFilter);

         } else if (credential.getImpersonateFilterScript() != null && realmFilter == null) {
             realms = repo.getAllList();
         } else if (credential.getImpersonateFilterScript() == null && realmFilter!= null) {
            RestError error = RestError.builder()
                    .status(Response.Status.PRECONDITION_FAILED .getStatusCode())
                    .statusMessage(String.format("For userId:%s impersonateFilter is null but realmFilter has been provided, invalid configuration, must specify a imPersonateFilter ", credential.getUserId())).build();
            return Response.status(Response.Status.PRECONDITION_FAILED).entity(error).build();
         } else {
            Optional<Realm> or = repo.findByRefName(credential.getDomainContext().getDefaultRealm());
            if (or.isPresent())
               realms = List.of(or.get());
            else
            {
               RestError error = RestError.builder()
                       .status(Response.Status.PRECONDITION_FAILED .getStatusCode())
                       .statusMessage(String.format("For userId:%s could not find default realm:%s in realms collection in realm:%s", credential.getUserId(), credential.getDomainContext().getDefaultRealm(), repo.getDatabaseName())).build();
               return Response.status(Response.Status.PRECONDITION_FAILED).entity(error).build();
            }
         }

      } else {
         RestError error = RestError.builder()
                .status(Response.Status.PRECONDITION_FAILED .getStatusCode())
                .statusMessage("possibleImpersonations/realms requires an authenticated user, get the SecurityContext PrincipalContext is not present, this indicates a logic error in the server.").build();
         return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
      }

      return Response.ok(realms).build();
   }

}

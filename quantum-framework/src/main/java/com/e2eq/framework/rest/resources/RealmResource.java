package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.filters.PermissionCheck;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.e2eq.framework.util.SecurityUtils;


@Path("/security/realm")
@RolesAllowed({ "admin", "system" })
@Tag(name = "security", description = "Operations related to security")
public class RealmResource extends BaseResource<Realm, BaseMorphiaRepo<Realm>> {
   @Inject
   CredentialRepo credentialRepo;

   @Inject
   SecurityUtils securityUtils;

   protected RealmResource (RealmRepo repo) {
      super(repo);
   }



   @Path("byTenantId")
   @GET
   @RolesAllowed({"user", "admin", "system"})
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
   @RolesAllowed({"user", "admin", "system"})
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

   @Path("accessible")
   @GET
   @Produces({ "application/json"})
   @Consumes({"application/json"})
   @RolesAllowed({"user", "admin", "system"})
   public Response accessible(@QueryParam("userId") String userId, @QueryParam("subjectId") String subjectId) {
      if ((userId == null || userId.isBlank()) && (subjectId == null || subjectId.isBlank())) {
         RestError error = RestError.builder()
                 .status(Response.Status.BAD_REQUEST.getStatusCode())
                 .statusMessage("Either userId or subjectId must be provided").build();
         return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
      }
      if (userId != null && !userId.isBlank() && subjectId != null && !subjectId.isBlank()) {
         RestError error = RestError.builder()
                 .status(Response.Status.BAD_REQUEST.getStatusCode())
                 .statusMessage("Specify only one of userId or subjectId, not both").build();
         return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
      }

      Optional<CredentialUserIdPassword> ocred;
      if (subjectId != null && !subjectId.isBlank()) {
         ocred = credentialRepo.findBySubject(subjectId, credentialRepo.getDatabaseName(), true);
      } else {
         ocred = credentialRepo.findByUserId(userId, credentialRepo.getDatabaseName(), true);
      }

      if (ocred.isEmpty()) {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("Credential not found for provided identifier").build();
         return Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }

      CredentialUserIdPassword cred = ocred.get();
      // Load all realms and compute allowed set
      List<Realm> all = repo.getAllList();
      List<String> candidateRefNames = all.stream().map(Realm::getRefName).collect(Collectors.toList());
      List<String> allowedRefNames = securityUtils.computeAllowedRealmRefNames(cred, candidateRefNames);
      if (!allowedRefNames.isEmpty()) {
         List<Realm> allowedRealms = all.stream()
                                        .filter(r -> allowedRefNames.stream().anyMatch(a -> a.equalsIgnoreCase(r.getRefName())))
                                        .collect(Collectors.toList());
         return Response.ok(allowedRealms).build();
      } else {
         return Response.ok(Collections.emptyList()).build();
      }
   }

}

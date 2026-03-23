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

import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;


@Path("/security/realm")
@RolesAllowed({ "admin", "system" })
@Tag(name = "security", description = "Operations related to security")
public class RealmResource extends BaseResource<Realm, BaseMorphiaRepo<Realm>> {
   @Inject
   CredentialRepo credentialRepo;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   EnvConfigUtils envConfigUtils;

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

      Optional<CredentialUserIdPassword> ocredential = credentialRepo.findByUserId(
              pContext.getUserId(),
              envConfigUtils.getSystemRealm(),
              true);
      if (ocredential.isEmpty()) {
         RestError error = RestError.builder()
                .status(Response.Status.PRECONDITION_FAILED .getStatusCode())
                .statusMessage("possibleImpersonations/realms requires an authenticated user, get the SecurityContext PrincipalContext is not present, this indicates a logic error in the server.").build();
         return Response.status(Response.Status.UNAUTHORIZED).entity(error).build();
      }

      List<Realm> realms = computeAllowedRealms(ocredential.get());
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

      String systemRealm = envConfigUtils.getSystemRealm();
      Optional<CredentialUserIdPassword> ocred;
      if (subjectId != null && !subjectId.isBlank()) {
         ocred = credentialRepo.findBySubject(subjectId, systemRealm, true);
      } else {
         ocred = credentialRepo.findByUserId(userId, systemRealm, true);
      }

      if (ocred.isEmpty()) {
         RestError error = RestError.builder()
                 .status(Response.Status.NOT_FOUND.getStatusCode())
                 .statusMessage("Credential not found for provided identifier").build();
         return Response.status(Response.Status.NOT_FOUND).entity(error).build();
      }

      return Response.ok(computeAllowedRealms(ocred.get())).build();
   }

   private List<Realm> computeAllowedRealms(CredentialUserIdPassword credential) {
      List<Realm> all = ((RealmRepo) repo).getAllListWithIgnoreRules(envConfigUtils.getSystemRealm());
      List<String> candidateRefNames = all.stream().map(Realm::getRefName).collect(Collectors.toList());
      List<String> allowedRefNames = securityUtils.computeAllowedRealmRefNames(credential, candidateRefNames);
      if (allowedRefNames.isEmpty()) {
         return Collections.emptyList();
      }
      return all.stream()
              .filter(r -> allowedRefNames.stream().anyMatch(a -> a.equalsIgnoreCase(r.getRefName())))
              .collect(Collectors.toList());
   }

}

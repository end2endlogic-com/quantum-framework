package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.auth.ApplicationAuthorizationResolver;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.rest.models.ApplicationAccessEvaluation;
import com.e2eq.framework.rest.models.ApplicationGrantRequest;
import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.rest.models.TenantGrantRequest;
import com.e2eq.framework.rest.core.BaseResource;
import com.e2eq.framework.service.application.ApplicationRegistryUnavailableException;
import com.e2eq.framework.service.application.ApplicationRegistryValidator;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.system.membership.RealmMembershipService;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.logging.Log;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Admin surface for per-realm role assignments (the GitHub-model membership list),
 * plus the application-scoped-auth grant. Standard CRUD comes from {@link BaseResource}
 * (parity with {@link RealmTenantMembershipResource}); the focused
 * {@code .../applications} endpoints are the authoritative, audited path for the
 * application grant and are pinned to the SYSTEM realm — the same store the login
 * path reads the grant back from — so they are correct regardless of the caller's
 * active realm.
 */
@Path("/security/user-realm-roles")
@RolesAllowed({ "admin", "system" })
@Tag(name = "security", description = "Operations related to security")
@IfBuildProperty(name = "quantum.system-rest.enabled", stringValue = "true", enableIfMissing = true)
public class UserRealmRoleResource extends BaseResource<UserRealmRole, UserRealmRoleRepo> {

   @Inject
   EnvConfigUtils envConfigUtils;

   @Inject
   ApplicationRegistryValidator applicationRegistryValidator;

   @Inject
   CredentialRepo credentialRepo;

   @Inject
   RealmRepo realmRepo;

   @Inject
   RealmMembershipService realmMembershipService;

   protected UserRealmRoleResource(UserRealmRoleRepo repo) {
      super(repo);
   }

   /**
    * Users granted {@code applicationId} in the identity store (system realm
    * UserRealmRole), not tenant userProfile collections. Optional
    * {@code realmRefName} limits the directory to one operating realm.
    */
   @GET
   @Path("assigned-users")
   @Produces(MediaType.APPLICATION_JSON)
   public Response listAssignedUsers(@jakarta.ws.rs.QueryParam("applicationId") String applicationId,
                                     @jakarta.ws.rs.QueryParam("realmRefName") String realmRefName) {
      String application = trimToNull(applicationId);
      if (application == null) {
         return Response.status(Response.Status.BAD_REQUEST)
                 .entity(RestError.builder()
                         .status(Response.Status.BAD_REQUEST.getStatusCode())
                         .statusMessage("applicationId is required")
                         .build())
                 .build();
      }
      String systemRealm = envConfigUtils.getSystemRealm();
      String realm = trimToNull(realmRefName);
      List<UserRealmRole> assignments = realm == null
              ? repo.findAllWithIgnoreRules(systemRealm)
              : repo.findByRealmRefNameWithIgnoreRules(realm, systemRealm);
      List<UserRealmRole> granted = assignmentsGrantingApplication(assignments, application);
      com.e2eq.framework.rest.models.Collection<UserRealmRole> collection =
              new com.e2eq.framework.rest.models.Collection<>(
                      granted, 0, granted.size(), null, (long) granted.size());
      collection.setRealm(realm == null ? systemRealm : realm);
      return Response.ok(collection).build();
   }

   static List<UserRealmRole> assignmentsGrantingApplication(
           List<UserRealmRole> assignments, String applicationId) {
      if (assignments == null || assignments.isEmpty()) {
         return List.of();
      }
      List<UserRealmRole> granted = new ArrayList<>();
      for (UserRealmRole assignment : assignments) {
         if (assignment == null || UserRealmRole.STATUS_SUSPENDED.equalsIgnoreCase(
                 assignment.getStatus() == null ? "" : assignment.getStatus())) {
            continue;
         }
         if (grantsApplication(assignment, applicationId)) {
            granted.add(assignment);
         }
      }
      return granted;
   }

   static boolean grantsApplication(UserRealmRole assignment, String applicationId) {
      List<String> granted = assignment.getAuthorizedApplications();
      if (granted == null || granted.isEmpty()) {
         return false;
      }
      return granted.stream().anyMatch(app ->
              app != null && ApplicationAuthorizationResolver.matches(app.trim(), applicationId));
   }

   /** Read the current application grant for a (user, realm) membership. */
   @GET
   @Path("{userId}/{realmRefName}/applications")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getApplicationGrant(@PathParam("userId") String userId,
                                       @PathParam("realmRefName") String realmRefName) {
      Optional<UserRealmRole> membership = loadMembership(userId, realmRefName);
      if (membership.isEmpty()) {
         return notFound(userId, realmRefName);
      }
      return Response.ok(membership.get()).build();
   }

   /**
    * Server-truth application-access evaluation: runs the SAME resolver a login
    * runs (list-or-* contract) against the user's stored credential pattern and
    * (user, realm) grant — without authenticating. Answers: what token scoping
    * would a login to this realm (optionally naming an application) produce?
    * AMBIGUOUS results with a pattern grant are enriched with candidates from
    * the realm catalog (the application-usage registry), like login is.
    */
   @GET
   @Path("{userId}/{realmRefName}/applications/evaluate")
   @Produces(MediaType.APPLICATION_JSON)
   public Response evaluateApplicationAccess(@PathParam("userId") String userId,
                                             @PathParam("realmRefName") String realmRefName,
                                             @jakarta.ws.rs.QueryParam("applicationId") String applicationId) {
      Optional<CredentialUserIdPassword> credentialOp =
              credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true);
      if (credentialOp.isEmpty()) {
         return Response.status(Response.Status.NOT_FOUND)
                 .entity(RestError.builder()
                         .status(Response.Status.NOT_FOUND.getStatusCode())
                         .statusMessage("No credential exists for userId '" + userId + "'")
                         .build())
                 .build();
      }
      CredentialUserIdPassword credential = credentialOp.get();
      Optional<UserRealmRole> membership = loadMembership(userId, realmRefName);
      List<String> granted = membership.map(UserRealmRole::getAuthorizedApplications).orElse(null);
      String defaultApplication = membership.map(UserRealmRole::getDefaultApplication).orElse(null);

      ApplicationAuthorizationResolver.Result result = ApplicationAuthorizationResolver.resolve(
              granted, credential.getApplicationRegEx(), defaultApplication, trimToNull(applicationId));

      List<String> candidates = result.candidates();
      if (result.outcome() == ApplicationAuthorizationResolver.Outcome.AMBIGUOUS && candidates.isEmpty()) {
         String pattern = credential.getApplicationRegEx();
         candidates = realmRepo.findDistinctApplicationRefNames().stream()
                 .filter(app -> ApplicationAuthorizationResolver.matches(pattern, app))
                 .toList();
      }

      return Response.ok(new ApplicationAccessEvaluation(
              userId,
              realmRefName,
              trimToNull(applicationId),
              result.outcome().name(),
              result.audiences(),
              result.activeApplication(),
              result.wildcard(),
              candidates,
              result.deniedApplication(),
              credential.getApplicationRegEx(),
              granted,
              defaultApplication,
              membership.isPresent())).build();
   }

   /**
    * Set (or clear) the application grant on a (user, realm) membership. Mutates only
    * the grant fields — roles and status are left untouched. An empty/null
    * authorizedApplications clears the grant (reverts the user to legacy behavior).
    */
   @PUT
   @Path("{userId}/{realmRefName}/applications")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response setApplicationGrant(@PathParam("userId") String userId,
                                       @PathParam("realmRefName") String realmRefName,
                                       ApplicationGrantRequest request) {
      Optional<UserRealmRole> membershipOp = loadMembership(userId, realmRefName);
      if (membershipOp.isEmpty()) {
         return notFound(userId, realmRefName);
      }
      UserRealmRole membership = membershipOp.get();

      List<String> apps = normalize(request == null ? null : request.getAuthorizedApplications());
      String defaultApp = trimToNull(request == null ? null : request.getDefaultApplication());

      if (apps.isEmpty()) {
         // Clear the grant → legacy (single-audience) behavior.
         membership.setAuthorizedApplications(null);
         membership.setDefaultApplication(null);
      } else {
         boolean wildcard = apps.contains(UserRealmRole.APPLICATION_WILDCARD);
         List<String> concrete = new ArrayList<>(apps);
         concrete.remove(UserRealmRole.APPLICATION_WILDCARD);

         if (defaultApp != null
                 && !UserRealmRole.APPLICATION_WILDCARD.equals(defaultApp)
                 && !concrete.contains(defaultApp)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(RestError.builder()
                            .status(Response.Status.BAD_REQUEST.getStatusCode())
                            .statusMessage("defaultApplication '" + defaultApp
                                    + "' must be one of authorizedApplications")
                            .build())
                    .build();
         }

         if (wildcard) {
            // "*" is an admin-only, deliberate grant — audit every time it is written.
            Log.warnf("Wildcard application grant WRITTEN (audit): user=%s realm=%s by=%s",
                    userId, realmRefName, callerName());
            apps = List.of(UserRealmRole.APPLICATION_WILDCARD);
            defaultApp = null;
         }

         // Grants must reference registered applications; the wildcard is
         // excluded (concrete) and the registry seam decides what "registered"
         // means for this deployment (allow-all in embedded/legacy mode).
         if (!concrete.isEmpty()) {
            java.util.Set<String> unknown;
            try {
               unknown = applicationRegistryValidator.unknownApplications(concrete);
            } catch (ApplicationRegistryUnavailableException e) {
               Log.errorf(e, "Application registry unavailable while validating grant: user=%s realm=%s",
                       userId, realmRefName);
               return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                       .entity(RestError.builder()
                               .status(Response.Status.SERVICE_UNAVAILABLE.getStatusCode())
                               .statusMessage("Application registry unavailable; grant not written: "
                                       + e.getMessage())
                               .build())
                       .build();
            }
            if (!unknown.isEmpty()) {
               return Response.status(Response.Status.BAD_REQUEST)
                       .entity(RestError.builder()
                               .status(Response.Status.BAD_REQUEST.getStatusCode())
                               .statusMessage("Unknown application id(s) — not in the application registry: "
                                       + String.join(", ", new java.util.TreeSet<>(unknown)))
                               .build())
                       .build();
            }
         }

         membership.setAuthorizedApplications(apps);
         membership.setDefaultApplication(defaultApp);
      }

      UserRealmRole saved = repo.save(envConfigUtils.getSystemRealm(), membership);
      return Response.ok(saved).build();
   }

   /** Clear the application grant (revert the membership to legacy behavior). */
   @DELETE
   @Path("{userId}/{realmRefName}/applications")
   @Produces(MediaType.APPLICATION_JSON)
   public Response clearApplicationGrant(@PathParam("userId") String userId,
                                         @PathParam("realmRefName") String realmRefName) {
      Optional<UserRealmRole> membershipOp = loadMembership(userId, realmRefName);
      if (membershipOp.isEmpty()) {
         return notFound(userId, realmRefName);
      }
      UserRealmRole membership = membershipOp.get();
      membership.setAuthorizedApplications(null);
      membership.setDefaultApplication(null);
      UserRealmRole saved = repo.save(envConfigUtils.getSystemRealm(), membership);
      return Response.ok(saved).build();
   }

   /** Read the tenant-selection grant for a (user, realm) assignment. */
   @GET
   @Path("{userId}/{realmRefName}/tenants")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getTenantGrant(@PathParam("userId") String userId,
                                  @PathParam("realmRefName") String realmRefName) {
      Optional<UserRealmRole> membership = loadMembership(userId, realmRefName);
      return membership.<Response>map(value -> Response.ok(value).build())
          .orElseGet(() -> notFound(userId, realmRefName));
   }

   /**
    * Replace the tenants a principal may select inside one realm. Every grant
    * must resolve to an active RealmTenantMembership; unknown tenants fail
    * closed and leave the existing assignment unchanged.
    */
   @PUT
   @Path("{userId}/{realmRefName}/tenants")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response setTenantGrant(@PathParam("userId") String userId,
                                  @PathParam("realmRefName") String realmRefName,
                                  TenantGrantRequest request) {
      Optional<UserRealmRole> membershipOp = loadMembership(userId, realmRefName);
      if (membershipOp.isEmpty()) {
         return notFound(userId, realmRefName);
      }
      List<String> tenants = normalize(
          request == null ? null : request.getAuthorizedTenantIds());
      String tenantPattern = trimToNull(
          request == null ? null : request.getAuthorizedTenantRegEx());
      if (tenantPattern != null && !"*".equals(tenantPattern)) {
         try {
            java.util.regex.Pattern.compile(tenantPattern, java.util.regex.Pattern.CASE_INSENSITIVE);
         } catch (java.util.regex.PatternSyntaxException invalidPattern) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("authorizedTenantRegEx is invalid: " + invalidPattern.getDescription())
                    .build())
                .build();
         }
      }
      for (String tenantId : tenants) {
         if (realmMembershipService.tenantInRealm(realmRefName, tenantId).isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(RestError.builder()
                    .status(Response.Status.BAD_REQUEST.getStatusCode())
                    .statusMessage("Tenant '" + tenantId
                        + "' is not active in realm '" + realmRefName + "'")
                    .build())
                .build();
         }
      }
      UserRealmRole membership = membershipOp.get();
      membership.setAuthorizedTenantIds(tenants);
      membership.setAuthorizedTenantRegEx(tenantPattern);
      UserRealmRole saved = repo.save(envConfigUtils.getSystemRealm(), membership);
      Log.infof("Tenant grant WRITTEN (audit): user=%s realm=%s tenants=%s tenantPattern=%s by=%s",
          userId, realmRefName, tenants, tenantPattern, callerName());
      return Response.ok(saved).build();
   }

   private Optional<UserRealmRole> loadMembership(String userId, String realmRefName) {
      return repo.findAssignmentForRealmWithIgnoreRules(userId, realmRefName, envConfigUtils.getSystemRealm());
   }

   private Response notFound(String userId, String realmRefName) {
      return Response.status(Response.Status.NOT_FOUND)
              .entity(RestError.builder()
                      .status(Response.Status.NOT_FOUND.getStatusCode())
                      .statusMessage("No realm membership for userId '" + userId
                              + "' in realm '" + realmRefName + "'")
                      .build())
              .build();
   }

   private String callerName() {
      try {
         return (jwt != null && jwt.getName() != null) ? jwt.getName() : "unknown";
      } catch (RuntimeException e) {
         return "unknown";
      }
   }

   private static List<String> normalize(List<String> apps) {
      if (apps == null) {
         return List.of();
      }
      LinkedHashSet<String> out = new LinkedHashSet<>();
      for (String app : apps) {
         String t = trimToNull(app);
         if (t != null) {
            out.add(t);
         }
      }
      return new ArrayList<>(out);
   }

   private static String trimToNull(String s) {
      if (s == null) {
         return null;
      }
      String t = s.trim();
      return t.isEmpty() ? null : t;
   }
}

package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.auth.RoleAssignment;
import com.e2eq.framework.model.auth.RoleSource;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.*;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.model.persistent.morphia.IdentityRoleResolver;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.lang.reflect.Method;
import java.util.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

@Path("/system/permissions")
@FunctionalMapping(area="system", domain="permission")
public class PermissionResource {

   @Inject
   @Default
   MorphiaDatastore datastore;

   @Inject
   FunctionalDomainRepo functionalDomainRepo;

   @Inject
   com.e2eq.framework.model.persistent.morphia.FunctionalAreaRepo functionalAreaRepo;

   @Inject
   com.e2eq.framework.model.persistent.morphia.ApplicationRepo applicationRepo;

   @Inject
   RuleContext ruleContext;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   com.e2eq.framework.model.persistent.morphia.CredentialRepo credentialRepo;

   @Inject
   com.e2eq.framework.util.EnvConfigUtils envConfigUtils;

   @Inject
   IdentityRoleResolver identityRoleResolver;

   @Inject
   SecurityIdentity securityIdentity;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   UserGroupRepo userGroupRepo;

   @Inject
   AuthProviderFactory authProviderFactory;

   @Inject
   com.e2eq.framework.service.access.AccessInviteService accessInviteService;

   private String inspectionRealm(String requested) {
      return requested != null && !requested.isBlank() ? requested :
              SecurityContext.getPrincipalContext().map(PrincipalContext::getDefaultRealm)
                      .orElse(ruleContext.getDefaultRealm());
   }

   /** Authorize before any target role, credential, profile or group resolution. */
   private void requireInspection(String identity, String realm, String tenant, String[] extraRoles) {
      PrincipalContext caller = SecurityContext.getPrincipalContext().orElse(null);
      boolean self = caller != null && identity.equals(caller.getUserId())
              && realm != null && realm.equals(caller.getDefaultRealm());
      Set<String> held = caller == null || caller.getRoles() == null ? Set.of()
              : new HashSet<>(Arrays.asList(caller.getRoles()));
      boolean heldExtras = extraRoles == null || held.containsAll(Arrays.asList(extraRoles));
      if (self && heldExtras) {
         return;
      }
      if (accessInviteService.mayInspectPermissions(realm, identity, tenant)) {
         return;
      }
      throw new ApplicationAccessException(ApplicationAccessException.Code.PERMISSION_INSPECTION_FORBIDDEN,
              "You may not inspect permissions for the requested scope.");
   }

   static class EntityInfo {
      public String entity;
      public String bmFunctionalArea;
      public String bmFunctionalDomain;
      public List<String> actions; // optional, included when requested
      EntityInfo(String entity, String area, String domain) {
         this.entity = entity;
         this.bmFunctionalArea = area;
         this.bmFunctionalDomain = domain;
      }
      EntityInfo(String entity, String area, String domain, List<String> actions) {
         this.entity = entity;
         this.bmFunctionalArea = area;
         this.bmFunctionalDomain = domain;
         this.actions = actions;
      }
   }

   // Request/Response DTOs for permission checks
   public static class CheckRequest {
      public String identity; // userId or role
      public String[] roles; // optional additional roles
      public String realm; // optional, defaults to RuleContext default realm
      public String applicationId; // optional only when inherited from the authenticated principal
      // DataDomain
      public String orgRefName;
      public String accountNumber;
      public String tenantId;
      public Integer dataSegment;
      public String ownerId; // optional override
      public String scope; // optional
      // ResourceContext
      public String area;
      public String functionalDomain;
      public String action;
      public String resourceId;

      // --- Optional fields to enable in-memory filter applicability on single-resource checks ---
      public String modelClass;                 // fully qualified class name or entity name
      public java.util.Map<String, Object> resource; // shallow JSON snapshot of the resource instance
      public Boolean enableFilterEval;          // explicit opt-in/out; defaults to true when both modelClass & resource provided
      public String evalMode;                   // optional: LEGACY | AUTO | STRICT (preferred over enableFilterEval)
   }

   public static class CheckWithIndexResponse {
      public SecurityCheckResponse check;
      public RuleIndexSnapshot index;
   }

   // Request and response for evaluating full access landscape for an identity
   public static class EvaluateRequest {
      public String identity;         // userId or role (required)
      public String realm;            // optional
      public String applicationId;    // optional only when inherited from the authenticated principal
      public String[] roles;          // optional extra roles
      // DataDomain scope (optional; falls back like /check)
      public String orgRefName;
      public String accountNumber;
      public String tenantId;
      public Integer dataSegment;
      public String ownerId;
      public String scope;            // optional, defaults to "api"
      // Optional narrowing filters
      public String area;
      public String functionalDomain;
      public String action;

      // --- Parity with /check: optional single-resource evaluation controls ---
      // When provided, allows the server to run in-memory evaluator per action where applicable
      public String modelClass;                 // fully qualified class name or entity name
      public java.util.Map<String, Object> resource; // shallow JSON snapshot
      public String evalMode;                   // LEGACY | AUTO | STRICT (default LEGACY)
   }

   public static class EvaluationResult {
      // area -> domain -> actions
      public Map<String, Map<String, List<String>>> allow;
      public Map<String, Map<String, List<String>>> deny;
      // New: detailed per-action decisions with SCOPED semantics (additive, backward-compatible)
      // decisions[area][domain][action] => EvaluationActionDecision
      public Map<String, Map<String, Map<String, EvaluationActionDecision>>> decisions;
      public String evalModeUsed;
   }

   public static class EvaluationActionDecision {
      public String effect;              // "ALLOW" | "DENY"
      public String decisionScope;       // "EXACT" | "SCOPED" | "DEFAULT"
      public boolean scopedConstraintsPresent;
      public List<SecurityCheckResponse.ScopedConstraint> scopedConstraints;
      public String naLabel;             // when DEFAULT
      public String rule;                // optional: winning rule name when available
      public Integer priority;           // optional
      public Boolean finalRule;          // optional
      public String source;              // optional (index source or identity)
   }

   // ===== Role Provenance API =====
   public static class RoleProvenanceRequest {
      public String userId;  // required
      public String realm;   // optional
   }

   public static class GroupInfo {
      public String refName;
      public List<String> roles;
   }

   public static class RoleProvenanceResponse {
      public String userId;
      public String realm;
      public boolean credentialFound;
      public List<String> credentialRoles;
      public boolean userProfileFound;
      public List<GroupInfo> userProfileGroups;          // groups linked via user profile membership
      public List<String> tokenRoles;                    // roles/groups present in current token (if principal matches userId)
      public List<GroupInfo> tokenMappedUserGroups;      // token roles mapped to user groups (if any)
      public Set<String> netRoles;                       // union of all discovered roles
      public List<RoleAssignment> assignments;           // role -> sources breakdown
      public Map<String, List<String>> notes;            // optional informational notes
   }

   @POST
   @Path("/role-provenance")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalAction("roleProvenance")
   @Authenticated
   @SecurityRequirement(name = "bearerAuth")
   public RoleProvenanceResponse roleProvenance(RoleProvenanceRequest req) {
      if (req == null || req.userId == null || req.userId.isBlank()) {
         throw new BadRequestException("userId is required");
      }

      String realm = inspectionRealm(req.realm);
      requireInspection(req.userId, realm, null, null);

      RoleProvenanceResponse resp = new RoleProvenanceResponse();
      resp.userId = req.userId;
      resp.realm = realm;
      resp.credentialFound = false;
      resp.userProfileFound = false;
      resp.credentialRoles = new ArrayList<>();
      resp.userProfileGroups = new ArrayList<>();
      resp.tokenRoles = new ArrayList<>();
      resp.tokenMappedUserGroups = new ArrayList<>();
      resp.notes = new LinkedHashMap<>();

      // Build provenance using centralized resolver (handles TOKEN, CREDENTIAL, USERGROUP via profile)
      Map<String, java.util.EnumSet<RoleSource>> provenance = identityRoleResolver.resolveRoleSources(req.userId, realm, securityIdentity);

      // Credential and profile/group exploration for display. Keep these lookups aligned
      // with IdentityRoleResolver so the explanatory flags cannot contradict the net roles.
      CredentialUserIdPassword cred=null;
      String systemRealm = envConfigUtils.getSystemRealm();
      try {
         var ocreds = credentialRepo.findByUserId(req.userId, systemRealm, true);
         if (ocreds.isEmpty() && !Objects.equals(systemRealm, realm)) {
            ocreds = credentialRepo.findByUserId(req.userId, realm, true);
         }
         if (ocreds.isPresent()) {
            resp.credentialFound = true;
            cred = ocreds.get();
            if (cred.getRoles() != null) resp.credentialRoles = Arrays.asList(cred.getRoles());

            try {
               Optional<UserProfile> userProfileOpt = userProfileRepo.getBySubject(systemRealm, cred.getSubject());
               if (userProfileOpt.isEmpty() && !Objects.equals(systemRealm, realm)) {
                  userProfileOpt = userProfileRepo.getBySubject(realm, cred.getSubject());
               }
               if (userProfileOpt.isPresent()) {
                  resp.userProfileFound = true;
                  var groups = userGroupRepo.findByUserProfileRef(userProfileOpt.get().createEntityReference());
                  if (groups != null) {
                     for (UserGroup g : groups) {
                        if (g == null) continue;
                        GroupInfo gi = new GroupInfo();
                        gi.refName = g.getRefName();
                        gi.roles = (g.getRoles() != null) ? new ArrayList<>(g.getRoles()) : List.of();
                        resp.userProfileGroups.add(gi);
                     }
                  }
               }
            } catch (Exception e) {
               resp.notes.computeIfAbsent("warnings", k -> new ArrayList<>()).add("Failed to expand user profile groups: " + e.getMessage());
            }
         }
      } catch (Exception e) {
         resp.notes.computeIfAbsent("errors", k -> new ArrayList<>()).add("Error reading credential/profile: " + e.getMessage());
      }

      // Token roles (only when the principal matches the checked userId)
      boolean principalMatches = false;
      if (securityIdentity != null) {
         String principalName = (securityIdentity.getPrincipal() != null) ? securityIdentity.getPrincipal().getName() : null;
         String attrUserId = null;
         try {
            Object attr = securityIdentity.getAttribute("userId");
            if (attr instanceof String s) attrUserId = s;
         } catch (Throwable ignored) {}
         principalMatches = (req.userId != null) && (
                 (principalName != null && req.userId.equals(principalName)) ||
                 (attrUserId != null && req.userId.equals(attrUserId))
         );
      }
      if (principalMatches) {
         for (String r : securityIdentity.getRoles()) {
            if (r != null && !r.isBlank()) resp.tokenRoles.add(r);
         }
         // Map token roles to UserGroups (if such groups exist), and include their roles for display
         for (String tokenRole : resp.tokenRoles) {
            try {
               userGroupRepo.findByRefName(tokenRole, realm).ifPresent(ug -> {
                  GroupInfo gi = new GroupInfo();
                  gi.refName = ug.getRefName();
                  gi.roles = (ug.getRoles() != null) ? new ArrayList<>(ug.getRoles()) : List.of();
                  resp.tokenMappedUserGroups.add(gi);
                  // Also reflect these roles in provenance as originating from USERGROUPs tied to IDP group mapping
                  if (ug.getRoles() != null) {
                     for (String r : ug.getRoles()) {
                        if (r == null || r.isBlank()) continue;
                        provenance.computeIfAbsent(r, k -> java.util.EnumSet.noneOf(RoleSource.class)).add(RoleSource.USERGROUP);
                     }
                  }
               });
            } catch (Exception ex) {
               resp.notes.computeIfAbsent("warnings", k -> new ArrayList<>()).add("Failed mapping token role '" + tokenRole + "' to UserGroup: " + ex.getMessage());
            }
         }
      } else {
         // The caller has already been authorized to inspect this user's IDP groups.
         if (authProviderFactory.getUserManager() != null && cred != null) {
            try {
               List<String> idpGroups = new ArrayList<>(authProviderFactory.getUserManager().getUserRolesForSubject(cred.getSubject()));
               for (String idpGroup : idpGroups) {
                  GroupInfo gi = new GroupInfo();
                  gi.refName = idpGroup;
                  gi.roles = List.of();
                  resp.tokenMappedUserGroups.add(gi);
               }
            } catch (Exception ex) {
               // The provider resolves the subject in the caller's own realm; an administrator of another
               // realm still gets the provenance, with the gap named, like the other display lookups here.
               resp.notes.computeIfAbsent("warnings", k -> new ArrayList<>()).add("Failed reading provider groups: " + ex.getMessage());
            }
         }
      }

      // Final union and assignments
      java.util.LinkedHashSet<String> net = new java.util.LinkedHashSet<>();
      for (String r : provenance.keySet()) {
         if (r != null && !r.isBlank()) net.add(r);
      }
      resp.netRoles = net;
      resp.assignments = identityRoleResolver.toAssignments(provenance);

      return resp;
   }

   protected List<EntityInfo> getInfoList() {
      return getInfoList(false);
   }

   protected List<EntityInfo> getInfoList(boolean includeActions) {
      MorphiaDatastore ds = datastore;
      List<EntityModel> entities = ds.getMapper().getMappedEntities();
      List<EntityInfo> infoList = new ArrayList<>();
      for (EntityModel em : entities) {
         String area = null;
         String domain = null;
         List<String> actions = null;
         try {
            Class<?> clazz = em.getType();
            // Prefer annotations if present
            com.e2eq.framework.annotations.FunctionalMapping fm = clazz.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
            if (fm != null) {
               area = fm.area();
               domain = fm.domain();
            } else {
               // Fall back to legacy bmFunctionalArea/bmFunctionalDomain methods via reflection
               Object instance = null;
               try {
                  instance = clazz.getDeclaredConstructor().newInstance();
               } catch (Throwable t) {
                  // ignore instantiation issues; leave area/domain null
               }
               if (instance != null) {
                  try {
                     Method mArea = clazz.getMethod("bmFunctionalArea");
                     Object a = mArea.invoke(instance);
                     area = a != null ? a.toString() : null;
                  } catch (NoSuchMethodException ignored) { }
                  try {
                     Method mDomain = clazz.getMethod("bmFunctionalDomain");
                     Object d = mDomain.invoke(instance);
                     domain = d != null ? d.toString() : null;
                  } catch (NoSuchMethodException ignored) { }
               }
            }

            if (includeActions) {
               // Collect actions from @FunctionalAction on methods
               Set<String> acts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
               for (Method m : clazz.getDeclaredMethods()) {
                  com.e2eq.framework.annotations.FunctionalAction fa = m.getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class);
                  if (fa != null) {
                     String val;
                     try {
                        val = fa.value();
                     } catch (Throwable ex) {
                        val = null;
                     }
                     if (val == null || val.isBlank()) {
                        acts.add(m.getName());
                     } else {
                        acts.add(val);
                     }
                  }
               }
               // Always ensure default actions are present
               acts.add("CREATE");
               acts.add("UPDATE");
               acts.add("DELETE");
               acts.add("VIEW");
               acts.add("LIST");
               actions = new ArrayList<>(acts);
            }
         } catch (Throwable t) {
            // swallow and continue; this endpoint is informational only
         }
         if (includeActions) {
            infoList.add(new EntityInfo(em.getName(), area, domain, actions));
         } else {
            infoList.add(new EntityInfo(em.getName(), area, domain));
         }
      }

      // sort by entity name for consistency
      infoList.sort((a, b) -> a.entity.compareToIgnoreCase(b.entity));
      return infoList;
   }

   @GET
   @Path("/entities")
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalAction("entities")
   @Authenticated
   @SecurityRequirement(name = "bearerAuth")
   public Response entities() {
      return Response.ok(getInfoList()).build();
   }

   @GET
   @Path("/fd")
   @Produces(MediaType.APPLICATION_JSON)
   @Authenticated
   @SecurityRequirement(name = "bearerAuth")
   public Response functionalDomains(@QueryParam("includeActions") @DefaultValue("false") boolean includeActions,
                                     @org.eclipse.microprofile.openapi.annotations.parameters.Parameter(
                                             description = "Answer only this application's registered vocabulary "
                                                     + "(its FunctionalArea and FunctionalDomain rows); 404 APPLICATION_UNKNOWN "
                                                     + "when the application is not registered. Without it, the union as before.")
                                     @QueryParam("applicationId") String applicationId) {
      if (applicationId != null && !applicationId.isBlank()) {
         String app = applicationId.trim();
         Response unknown = unknownApplication(app);
         if (unknown != null) return unknown;
         return Response.ok(shapeVocabulary(registeredVocabulary(app), includeActions)).build();
      }
      // Unified structure: area -> domain -> actions (case-insensitive)
      Map<String, Map<String, Set<String>>> areaDomainActions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

      // 1) From model discovery
      List<EntityInfo> infoList = getInfoList(includeActions);
      for (EntityInfo ei : infoList) {
         if (ei == null) continue;
         String area = safe(ei.bmFunctionalArea);
         String domain = safe(ei.bmFunctionalDomain);
         if (area.isEmpty() || domain.isEmpty()) continue;

         Set<String> actions = ensureAreaDomain(areaDomainActions, area, domain);
         if (includeActions) {
            // Entity-backed domains genuinely serve generic CRUD via BaseResource.
            if (ei.actions != null) actions.addAll(ei.actions);
            addDefaultActions(actions);
         }
      }

      // 1.5) From REST resources' @FunctionalMapping/@FunctionalAction — their
      // DECLARED verbs only (a browse resource is view-only, a profile resource
      // declares password verbs); never widened to CRUD.
      mergeResourceMappings(areaDomainActions, discoverResourceMappings(), includeActions);

      // 2) From DB (FunctionalDomain collection)
      List<FunctionalDomain> stored = functionalDomainRepo.getAllList();
      if (stored != null) {
         for (FunctionalDomain fd : stored) {
            if (fd == null) continue;
            String area = safe(fd.getArea());
            String domain = safe(fd.getRefName());
            if (area.isEmpty() || domain.isEmpty()) continue;

            Set<String> actions = ensureAreaDomain(areaDomainActions, area, domain);
            if (includeActions) {
               boolean declaredAny = false;
               if (fd.getFunctionalActions() != null) {
                  for (com.e2eq.framework.model.security.FunctionalAction a : fd.getFunctionalActions()) {
                     if (a == null) continue;
                     String ref = safe(a.getRefName());
                     if (!ref.isEmpty()) { actions.add(ref); declaredAny = true; }
                  }
               }
               // A record that DECLARES its actions means them; CRUD is only the
               // fallback for records that declare nothing.
               if (!declaredAny) addDefaultActions(actions);
            }
         }
      }

      return Response.ok(shapeVocabulary(areaDomainActions, includeActions)).build();
   }

   /**
    * The functional areas an application owns, as registered with this service (its identity
    * contribution's {@code functionalArea} rows). {@code applicationId} is required: an area belongs
    * to exactly one application.
    */
   @GET
   @Path("/fd/areas")
   @Produces(MediaType.APPLICATION_JSON)
   @Authenticated
   @SecurityRequirement(name = "bearerAuth")
   @org.eclipse.microprofile.openapi.annotations.Operation(operationId = "listApplicationFunctionalAreas",
           summary = "An application's functional areas",
           description = "The FunctionalArea records the application owns, as registered with this service "
                   + "(its identity contribution), sorted by refName.")
   @org.eclipse.microprofile.openapi.annotations.responses.APIResponse(responseCode = "200", description = "The areas",
           content = @org.eclipse.microprofile.openapi.annotations.media.Content(mediaType = MediaType.APPLICATION_JSON,
                   schema = @org.eclipse.microprofile.openapi.annotations.media.Schema(
                           type = org.eclipse.microprofile.openapi.annotations.enums.SchemaType.ARRAY,
                           implementation = FunctionalArea.class)))
   @org.eclipse.microprofile.openapi.annotations.responses.APIResponse(responseCode = "400", description = "applicationId is required")
   @org.eclipse.microprofile.openapi.annotations.responses.APIResponse(responseCode = "404", description = "APPLICATION_UNKNOWN")
   public Response functionalAreas(@QueryParam("applicationId") String applicationId) {
      if (applicationId == null || applicationId.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("applicationId is required").build();
      }
      String app = applicationId.trim();
      Response unknown = unknownApplication(app);
      if (unknown != null) return unknown;
      List<FunctionalArea> areas = new ArrayList<>(ownedBy(governedList(functionalAreaRepo, "FUNCTIONAL_AREA"),
              FunctionalArea::getApplicationId, app));
      areas.sort(Comparator.comparing(a -> safe(a.getRefName()), String.CASE_INSENSITIVE_ORDER));
      return Response.ok(areas).build();
   }

   /**
    * 404 naming the application when the caller cannot see it registered here (absent and hidden answer
    * alike); {@code null} when it can. The lookup runs the caller's rules on SECURITY/APPLICATION.
    */
   private Response unknownApplication(String applicationId) {
      boolean visible;
      try (SecurityCallScope.Scope ignored = SecurityCallScope.openResourceOnly(vocabularyResource("APPLICATION", "VIEW"))) {
         visible = applicationRepo.findByRefName(applicationId).isPresent();
      }
      if (visible) {
         return null;
      }
      return Response.status(Response.Status.NOT_FOUND)
              .entity(Map.of("code", "APPLICATION_UNKNOWN",
                      "message", "application '" + applicationId + "' is not registered with this service"))
              .build();
   }

   /**
    * The caller's own rules on the vocabulary records themselves (SECURITY/{@code domain}), not on this
    * resource's system/permission context, so a deny or filter on those records holds here too.
    */
   private ResourceContext vocabularyResource(String domain, String action) {
      ResourceContext current = SecurityContext.getResourceContext().orElse(null);
      return new ResourceContext.Builder()
              .withRealm(current == null ? null : current.getRealm())
              .withApplicationId(current == null ? null : current.getApplicationId())
              .withArea("SECURITY")
              .withFunctionalDomain(domain)
              .withAction(action)
              .build();
   }

   private <T extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> List<T> governedList(
           com.e2eq.framework.model.persistent.morphia.MorphiaRepo<T> repo, String domain) {
      try (SecurityCallScope.Scope ignored = SecurityCallScope.openResourceOnly(vocabularyResource(domain, "LIST"))) {
         return repo.getAllList();
      }
   }

   /** Rows tagged with exactly {@code applicationId} ('*' and untagged rows are nobody's own). */
   private static <T> List<T> ownedBy(List<T> rows, java.util.function.Function<T, String> tag, String applicationId) {
      List<T> out = new ArrayList<>();
      if (rows == null) return out;
      for (T row : rows) {
         if (row == null) continue;
         String owner = tag.apply(row);
         if (owner != null && owner.trim().equalsIgnoreCase(applicationId)) out.add(row);
      }
      return out;
   }

   /**
    * One application's registered vocabulary: its FunctionalArea rows (an area may hold no domain yet)
    * and its FunctionalDomain rows with their declared actions. Code discovered in this service and other
    * applications' rows are not part of it.
    */
   private Map<String, Map<String, Set<String>>> registeredVocabulary(String applicationId) {
      Map<String, Map<String, Set<String>>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (FunctionalArea area : ownedBy(governedList(functionalAreaRepo, "FUNCTIONAL_AREA"), FunctionalArea::getApplicationId, applicationId)) {
         String name = safe(area.getRefName());
         if (!name.isEmpty()) out.computeIfAbsent(name, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
      }
      for (FunctionalDomain fd : ownedBy(governedList(functionalDomainRepo, "FUNCTIONAL_DOMAIN"), FunctionalDomain::getApplicationId, applicationId)) {
         String area = safe(fd.getArea());
         String domain = safe(fd.getRefName());
         if (area.isEmpty() || domain.isEmpty()) continue;
         Set<String> actions = ensureAreaDomain(out, area, domain);
         boolean declaredAny = false;
         if (fd.getFunctionalActions() != null) {
            for (com.e2eq.framework.model.security.FunctionalAction a : fd.getFunctionalActions()) {
               if (a == null) continue;
               String ref = safe(a.getRefName());
               if (!ref.isEmpty()) { actions.add(ref); declaredAny = true; }
            }
         }
         if (!declaredAny) addDefaultActions(actions);
      }
      return out;
   }

   /** area -> sorted domains, or with {@code includeActions} area -> domain -> sorted actions. */
   private static Object shapeVocabulary(Map<String, Map<String, Set<String>>> areaDomainActions, boolean includeActions) {
      if (!includeActions) {
         // area -> sorted list of domains (union from both sources)
         Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
         for (Map.Entry<String, Map<String, Set<String>>> e : areaDomainActions.entrySet()) {
            List<String> domains = new ArrayList<>(e.getValue().keySet());
            domains.sort(String::compareToIgnoreCase);
            result.put(e.getKey(), domains);
         }
         return result;
      }

      // includeActions=true: area -> domain -> sorted list of actions
      Map<String, Map<String, List<String>>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (Map.Entry<String, Map<String, Set<String>>> e : areaDomainActions.entrySet()) {
         Map<String, List<String>> dom = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
         for (Map.Entry<String, Set<String>> d : e.getValue().entrySet()) {
            List<String> actions = new ArrayList<>(d.getValue());
            actions.sort(String::compareToIgnoreCase);
            dom.put(d.getKey(), actions);
         }
         out.put(e.getKey(), dom);
      }
      return out;
   }

   private static Set<String> ensureAreaDomain(Map<String, Map<String, Set<String>>> areaDomainActions,
                                               String area,
                                               String domain) {
      Map<String, Set<String>> dmap = areaDomainActions.computeIfAbsent(area, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
      return dmap.computeIfAbsent(domain, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
   }

   private static void addDefaultActions(Set<String> target) {
      target.add("CREATE");
      target.add("UPDATE");
      target.add("DELETE");
      target.add("VIEW");
      target.add("LIST");
   }

   /**
    * Domains declared by REST resources via {@code @FunctionalMapping}, with the
    * ACTIONS THEY ACTUALLY DECLARE via {@code @FunctionalAction} methods. Actions
    * are not a fixed CRUD set — a user-profile resource declares verbs like a
    * password reset, a browse resource declares only {@code view}. A resource
    * that declares a mapping but no explicit actions falls back to the CRUD
    * defaults (we know nothing better). Entity-backed domains keep their CRUD
    * defaults elsewhere — BaseResource genuinely serves them.
    */
   private Map<String, Map<String, Set<String>>> discoverResourceMappings() {
      Map<String, Map<String, Set<String>>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      try {
         jakarta.enterprise.inject.spi.BeanManager beanManager =
                 jakarta.enterprise.inject.spi.CDI.current().getBeanManager();
         java.util.Set<jakarta.enterprise.inject.spi.Bean<?>> beans =
                 beanManager.getBeans(Object.class, jakarta.enterprise.inject.Any.Literal.INSTANCE);
         io.quarkus.logging.Log.debugf("Resource @FunctionalMapping discovery scanning %d beans", beans.size());
         for (jakarta.enterprise.inject.spi.Bean<?> bean : beans) {
            Class<?> clazz = bean.getBeanClass();
            if (clazz == null) continue;
            com.e2eq.framework.annotations.FunctionalMapping fm =
                    clazz.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
            if (fm == null) continue;
            // Only REST resources contribute here; entities are discovered from the model.
            if (clazz.getAnnotation(jakarta.ws.rs.Path.class) == null) continue;
            String area = safe(fm.area());
            String domain = safe(fm.domain());
            if (area.isEmpty() || domain.isEmpty()) continue;
            Set<String> declared = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Method m : clazz.getDeclaredMethods()) {
               com.e2eq.framework.annotations.FunctionalAction fa =
                       m.getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class);
               if (fa == null) continue;
               String val = safe(fa.value());
               declared.add(val.isEmpty() ? m.getName() : val);
            }
            Set<String> actions = ensureAreaDomain(out, area, domain);
            if (declared.isEmpty()) {
               addDefaultActions(actions);
            } else {
               actions.addAll(declared);
            }
         }
      } catch (Throwable t) {
         io.quarkus.logging.Log.warnf("Resource @FunctionalMapping discovery skipped: %s", t);
      }
      io.quarkus.logging.Log.debugf("Resource @FunctionalMapping discovery found %d areas: %s", out.size(), out.keySet());
      return out;
   }

   /** Merge resource-declared mappings into a discovery map without widening declared action sets. */
   private static void mergeResourceMappings(Map<String, Map<String, Set<String>>> target,
                                             Map<String, Map<String, Set<String>>> resourceMappings,
                                             boolean includeActions) {
      for (Map.Entry<String, Map<String, Set<String>>> areaEntry : resourceMappings.entrySet()) {
         for (Map.Entry<String, Set<String>> domainEntry : areaEntry.getValue().entrySet()) {
            Set<String> actions = ensureAreaDomain(target, areaEntry.getKey(), domainEntry.getKey());
            if (includeActions) actions.addAll(domainEntry.getValue());
         }
      }
   }


   private static String safe(String s) { return (s == null) ? "" : s.trim(); }

   private Set<Class<?>> listClasses(String packageName) {
      try {
         ClassLoader loader = Thread.currentThread().getContextClassLoader();
         String path = packageName.replace('.', '/');
         InputStream in = loader.getResourceAsStream(path);
         if (in == null) return Collections.emptySet();
         BufferedReader reader = new BufferedReader(new InputStreamReader(in));
         Set<Class<?>> classes = new HashSet<>();
         for (String line; (line = reader.readLine()) != null; ) {
            if (!line.endsWith(".class")) continue;
            String clsName = line.substring(0, line.length() - 6);
            try {
               Class<?> c = Class.forName(packageName + "." + clsName);
               classes.add(c);
            } catch (Throwable ignored) { }
         }
         return classes;
      } catch (Throwable t) {
         return Collections.emptySet();
      }
   }

   @POST
   @Path("/check")
   @Authenticated
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalAction("check")
   public Response check(CheckRequest req) {
      if (req == null || req.identity == null || req.identity.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("identity is required").build();
      }
      // Prefer explicit realm, then SecurityContext principal realm, then default
      String realm = inspectionRealm(req.realm);
      requireInspection(req.identity, realm, req.tenantId, req.roles);
      String applicationId = (req.applicationId != null && !req.applicationId.isBlank()) ? req.applicationId :
              SecurityContext.getPrincipalContext().map(PrincipalContext::getApplicationId).orElse(null);
      if (applicationId == null || applicationId.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("applicationId is required").build();
      }

      // Build DataDomain with fallbacks (request -> SecurityContext principal -> framework default)
      String org = (req.orgRefName != null && !req.orgRefName.isBlank()) ? req.orgRefName :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getOrgRefName()).orElse(securityUtils.getDefaultDataDomain().getOrgRefName());
      String acct = (req.accountNumber != null && !req.accountNumber.isBlank()) ? req.accountNumber :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getAccountNum()).orElse(securityUtils.getDefaultDataDomain().getAccountNum());
      String tenant = (req.tenantId != null && !req.tenantId.isBlank()) ? req.tenantId :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getTenantId()).orElse(securityUtils.getDefaultDataDomain().getTenantId());
      int seg = (req.dataSegment != null) ? req.dataSegment :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getDataSegment()).orElse(securityUtils.getDefaultDataDomain().getDataSegment());

      // OwnerId: prefer request, then ResourceContext ownerId, then principal userId, finally request identity
      String ownerId = (req.ownerId != null && !req.ownerId.isBlank()) ? req.ownerId :
              SecurityContext.getResourceContext().map(ResourceContext::getOwnerId)
                      .or(() -> SecurityContext.getPrincipalContext().map(PrincipalContext::getUserId))
                      .orElse(req.identity);

      DataDomain dd = new DataDomain(org, acct, tenant, seg, ownerId);

      // Resolve roles using centralized resolver: includes token roles, credential roles, and user group roles when identity is a userId
      Set<String> resolvedIdentities = identityRoleResolver.resolveRolesForIdentity(req.identity, realm, applicationId, securityIdentity);
      // PrincipalContext.roles should contain only role names; remove the userId if present
      if (resolvedIdentities != null) {
         resolvedIdentities.remove(req.identity);
      }
      // Include any additional roles explicitly provided by the client
      if (req.roles != null) {
         resolvedIdentities.addAll(Arrays.asList(req.roles));
      }
      String[] roles = resolvedIdentities.isEmpty() ? new String[0] : resolvedIdentities.toArray(new String[0]);

      PrincipalContext pc = new PrincipalContext.Builder()
              .withDefaultRealm(realm)
              .withApplicationId(applicationId)
              .withDataDomain(dd)
              .withUserId(req.identity)
              .withRoles(roles)
              .withScope(req.scope != null ? req.scope : "api")
              .build();

      // Normalize action to match SecurityFilter behavior (converts "list" -> "LIST")
      String normalizedAction = req.action;
      if (normalizedAction != null && "list".equalsIgnoreCase(normalizedAction)) {
         normalizedAction = "LIST";
      }

      // Do NOT override FunctionalDomain, Area, or Action if scripts supply them; use request or wildcard
      ResourceContext rc = new ResourceContext.Builder()
              .withRealm(realm)
              .withApplicationId(applicationId)
              .withArea(req.area != null ? req.area : "*")
              .withFunctionalDomain(req.functionalDomain != null ? req.functionalDomain : "*")
              .withAction(normalizedAction != null ? normalizedAction : "*")
              .withResourceId(req.resourceId)
              .withOwnerId(ownerId)
              .build();

      // Map eval mode (request param preferred over deprecated enableFilterEval)
      com.e2eq.framework.model.securityrules.EvalMode mode = com.e2eq.framework.model.securityrules.EvalMode.LEGACY;
      if (req != null && req.evalMode != null && !req.evalMode.isBlank()) {
         try {
            mode = com.e2eq.framework.model.securityrules.EvalMode.valueOf(req.evalMode.trim().toUpperCase());
         } catch (IllegalArgumentException ignored) { /* keep LEGACY */ }
      } else if (req != null && req.enableFilterEval != null && req.enableFilterEval) {
         // Deprecated shim: treat enableFilterEval=true as AUTO when resource+modelClass provided
         mode = com.e2eq.framework.model.securityrules.EvalMode.AUTO;
      }

      // Always route through the evalMode overload to centralize behavior and tracing.
      Class<?> mc = null;
      if (req.modelClass != null && !req.modelClass.isBlank()) {
         try {
            mc = Class.forName(req.modelClass.trim());
         } catch (Throwable t) {
            if (io.quarkus.logging.Log.isDebugEnabled()) {
               io.quarkus.logging.Log.debugf(t, "PermissionResource: modelClass '%s' could not be resolved; proceeding without", req.modelClass);
            }
            mc = null;
         }
      }
      @SuppressWarnings("unchecked")
      Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> typed = (Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel>) mc;
      java.util.Map<String, Object> resourceMap = (req.resource != null ? req.resource : null);
      SecurityCheckResponse resp = ruleContext.checkRules(pc, rc, typed, resourceMap, com.e2eq.framework.model.securityrules.RuleEffect.DENY, mode);

      // Enrich roleAssignments with provenance (IDP, CREDENTIAL, USERGROUP)
      try {
         Map<String, java.util.EnumSet<com.e2eq.framework.model.auth.RoleSource>> provenance =
                 identityRoleResolver.resolveRoleSources(req.identity, realm, applicationId, securityIdentity);

         // Ensure all final roles are covered in the assignments, even if no sources are known
         for (String r : roles) {
            if (r == null || r.isBlank()) continue;
            provenance.computeIfAbsent(r, k -> java.util.EnumSet.noneOf(com.e2eq.framework.model.auth.RoleSource.class));
         }

         var roleAssignments = identityRoleResolver.toAssignments(provenance);
         resp.setRoleAssignments(roleAssignments);
      } catch (Exception e) {
         // keep default assignments on failure
         ExceptionLoggingUtils.logWarn(e, "Failed to resolve role assignments, using defaults");
      }

      return Response.ok(resp).build();
   }


   /**
    * Everything one identity may do in one application, decided by the rule engine.
    *
    * <p>Each action the application registers (its FunctionalArea and FunctionalDomain rows) is
    * evaluated with {@link RuleContext#checkRules} exactly as a request would be: the realm's and the
    * application's policies, rule precedence, wildcard patterns and scripts all apply. The allowed actions
    * are returned as one scoped matrix with exact, lower-case keys, so a client can show or hide actions
    * without another call. Only allowed actions have a cell; a client treats a missing cell as denied. The
    * backend still enforces every request.</p>
    *
    * <p>The application's vocabulary is read with the service's own authority, because a person who may
    * not list vocabulary records may still ask what they themselves may do. That is why only allowed
    * actions are returned: the answer discloses nothing the person could not find out by acting.</p>
    *
    * <p>{@code area}, {@code functionalDomain} and {@code action} narrow the evaluation when given.
    * {@code roles} adds no role to the snapshot.</p>
    */
   @POST
   @Path("/check-with-index")
   @Authenticated
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalAction("check-with-index")
   public Response checkWithIndex(CheckRequest req) {
      if (req == null || req.identity == null || req.identity.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("identity is required").build();
      }
      String realm = inspectionRealm(req.realm);
      requireInspection(req.identity, realm, req.tenantId, req.roles);
      String applicationId = requestApplicationId(req.applicationId);
      if (applicationId == null) {
         return Response.status(Response.Status.BAD_REQUEST).entity("applicationId is required").build();
      }
      Map<String, Map<String, Set<String>>> vocabulary;
      try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
         if (applicationRepo.findByRefName(applicationId).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("code", "APPLICATION_UNKNOWN",
                            "message", "application '" + applicationId + "' is not registered with this service"))
                    .build();
         }
         vocabulary = registeredVocabulary(applicationId);
      }
      narrow(vocabulary, req.area, req.functionalDomain, req.action);

      // The snapshot is the identity's own: its owner is that identity unless the request names another.
      String ownerId = (req.ownerId != null && !req.ownerId.isBlank()) ? req.ownerId : req.identity;
      DataDomain dd = requestDataDomain(req.orgRefName, req.accountNumber, req.tenantId, req.dataSegment, ownerId, req.identity);
      Set<String> roles = evaluationRoles(req.identity, realm, applicationId, null);
      PrincipalContext pc = evaluationPrincipal(req.identity, roles, realm, applicationId, dd, req.scope);
      Map<String, Map<String, Map<String, EvaluationActionDecision>>> decisions =
              engineDecisions(pc, vocabulary, realm, applicationId, dd.getOwnerId(), null, null,
                      com.e2eq.framework.model.securityrules.EvalMode.LEGACY);

      Set<String> sources = new TreeSet<>(roles);
      sources.add(req.identity);
      return Response.ok(accessSnapshot(decisions, sources, dd)).build();
   }

   /** The snapshot shape clients already read: one scope, exact lower-case cells for allowed actions only. */
   private RuleIndexSnapshot accessSnapshot(Map<String, Map<String, Map<String, EvaluationActionDecision>>> decisions,
                                            Set<String> sources, DataDomain dd) {
      RuleIndexSnapshot snap = new RuleIndexSnapshot();
      snap.setEnabled(true);
      snap.setVersion(ruleContext.getPolicyVersion());
      snap.setPolicyVersion(ruleContext.getPolicyVersion());
      snap.setRequiresServer(false);
      snap.getSources().addAll(sources);
      RuleIndexSnapshot.ScopedMatrix scope = new RuleIndexSnapshot.ScopedMatrix();
      for (Map.Entry<String, Map<String, Map<String, EvaluationActionDecision>>> area : decisions.entrySet()) {
         for (Map.Entry<String, Map<String, EvaluationActionDecision>> domain : area.getValue().entrySet()) {
            for (Map.Entry<String, EvaluationActionDecision> action : domain.getValue().entrySet()) {
               EvaluationActionDecision decision = action.getValue();
               if (!"ALLOW".equalsIgnoreCase(decision.effect)) continue;
               RuleIndexSnapshot.Outcome outcome = new RuleIndexSnapshot.Outcome();
               outcome.setEffect("ALLOW");
               outcome.setRule(decision.rule);
               outcome.setPriority(decision.priority != null ? decision.priority : 0);
               outcome.setFinalRule(Boolean.TRUE.equals(decision.finalRule));
               scope.getMatrix()
                       .computeIfAbsent(lower(area.getKey()), k -> new HashMap<>())
                       .computeIfAbsent(lower(domain.getKey()), k -> new HashMap<>())
                       .put(lower(action.getKey()), outcome);
            }
         }
      }
      String key = scopeKey(dd);
      snap.getScopes().put(key, scope);
      snap.setRequestedScope(key);
      snap.setRequestedFallback(List.of(key));
      return snap;
   }

   /** The scope key of a data domain, lower case, as clients compute it. */
   static String scopeKey(DataDomain dd) {
      return "org=" + scopePart(dd.getOrgRefName())
              + "|acct=" + scopePart(dd.getAccountNum())
              + "|tenant=" + scopePart(dd.getTenantId())
              + "|seg=" + dd.getDataSegment()
              + "|owner=" + scopePart(dd.getOwnerId());
   }

   private static String scopePart(String value) {
      return value == null || value.isBlank() ? "*" : value.trim().toLowerCase(Locale.ROOT);
   }

   private static String lower(String value) {
      return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
   }

   /** The request's application, else the authenticated principal's; null when neither names one. */
   private static String requestApplicationId(String requested) {
      String applicationId = (requested != null && !requested.isBlank()) ? requested.trim() :
              SecurityContext.getPrincipalContext().map(PrincipalContext::getApplicationId).orElse(null);
      return (applicationId == null || applicationId.isBlank()) ? null : applicationId;
   }

   /** The data domain an evaluation runs in: the request's fields, else the caller's, else the default. */
   private DataDomain requestDataDomain(String orgRefName, String accountNumber, String tenantId, Integer dataSegment,
                                        String ownerId, String identity) {
      String org = (orgRefName != null && !orgRefName.isBlank()) ? orgRefName :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getOrgRefName()).orElse(securityUtils.getDefaultDataDomain().getOrgRefName());
      String acct = (accountNumber != null && !accountNumber.isBlank()) ? accountNumber :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getAccountNum()).orElse(securityUtils.getDefaultDataDomain().getAccountNum());
      String tenant = (tenantId != null && !tenantId.isBlank()) ? tenantId :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getTenantId()).orElse(securityUtils.getDefaultDataDomain().getTenantId());
      int seg = (dataSegment != null) ? dataSegment :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getDataSegment()).orElse(securityUtils.getDefaultDataDomain().getDataSegment());
      String owner = (ownerId != null && !ownerId.isBlank()) ? ownerId :
              SecurityContext.getResourceContext().map(ResourceContext::getOwnerId)
                      .or(() -> SecurityContext.getPrincipalContext().map(PrincipalContext::getUserId))
                      .orElse(identity);
      return new DataDomain(org, acct, tenant, seg, owner);
   }

   /** The identity's roles for this realm and application, plus any "what if" roles; never the identity itself. */
   private Set<String> evaluationRoles(String identity, String realm, String applicationId, String[] extraRoles) {
      Set<String> roles = identityRoleResolver.resolveRolesForIdentity(identity, realm, applicationId, securityIdentity);
      Set<String> out = roles == null ? new HashSet<>() : new HashSet<>(roles);
      if (extraRoles != null) out.addAll(Arrays.asList(extraRoles));
      out.remove(identity);
      out.removeIf(role -> role == null || role.isBlank());
      return out;
   }

   private static PrincipalContext evaluationPrincipal(String identity, Set<String> roles, String realm, String applicationId,
                                                       DataDomain dd, String scope) {
      return new PrincipalContext.Builder()
              .withDefaultRealm(realm)
              .withApplicationId(applicationId)
              .withDataDomain(dd)
              .withUserId(identity)
              .withRoles(roles.toArray(new String[0]))
              .withScope(scope != null ? scope : "api")
              .build();
   }

   /** Keep only the area, domain and action asked for; a blank filter keeps everything. */
   private static Map<String, Map<String, Set<String>>> narrow(Map<String, Map<String, Set<String>>> vocabulary,
                                                               String area, String functionalDomain, String action) {
      if (area != null && !area.isBlank()) {
         vocabulary.keySet().removeIf(a -> !a.equalsIgnoreCase(area));
      }
      if (functionalDomain != null && !functionalDomain.isBlank()) {
         for (Map<String, Set<String>> domains : vocabulary.values()) {
            domains.keySet().removeIf(d -> !d.equalsIgnoreCase(functionalDomain));
         }
      }
      if (action != null && !action.isBlank()) {
         for (Map<String, Set<String>> domains : vocabulary.values()) {
            for (Set<String> actions : domains.values()) {
               actions.removeIf(a -> !a.equalsIgnoreCase(action));
            }
         }
      }
      return vocabulary;
   }

   /**
    * The rule engine's decision for every action in {@code vocabulary}: the same {@link RuleContext#checkRules}
    * a request gets, so realm and application policies, precedence, patterns and scripts all count.
    */
   private Map<String, Map<String, Map<String, EvaluationActionDecision>>> engineDecisions(
           PrincipalContext pc, Map<String, Map<String, Set<String>>> vocabulary, String realm, String applicationId,
           String ownerId, Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> modelClass,
           Map<String, Object> resource, com.e2eq.framework.model.securityrules.EvalMode mode) {
      Map<String, Map<String, Map<String, EvaluationActionDecision>>> decisions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (Map.Entry<String, Map<String, Set<String>>> areaEntry : vocabulary.entrySet()) {
         String areaKey = areaEntry.getKey();
         Map<String, Map<String, EvaluationActionDecision>> domDecisions = decisions.computeIfAbsent(areaKey, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
         for (Map.Entry<String, Set<String>> domEntry : areaEntry.getValue().entrySet()) {
            String domainKey = domEntry.getKey();
            Map<String, EvaluationActionDecision> actDecisions = domDecisions.computeIfAbsent(domainKey, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
            for (String action : domEntry.getValue()) {
               ResourceContext rc = new ResourceContext.Builder()
                       .withRealm(realm)
                       .withApplicationId(applicationId)
                       .withArea(areaKey)
                       .withFunctionalDomain(domainKey)
                       .withAction(action)
                       .withOwnerId(ownerId)
                       .build();
               SecurityCheckResponse resp = ruleContext.checkRules(pc, rc, modelClass, resource, RuleEffect.DENY, mode);
               String effect = (resp.getDecision() != null ? resp.getDecision() : (resp.getFinalEffect() != null ? resp.getFinalEffect().name() : "DENY"));
               EvaluationActionDecision actionDecision = new EvaluationActionDecision();
               actionDecision.effect = effect;
               actionDecision.decisionScope = (resp.getDecisionScope() != null ? resp.getDecisionScope() : "DEFAULT");
               actionDecision.scopedConstraintsPresent = resp.isScopedConstraintsPresent();
               actionDecision.scopedConstraints = (resp.getScopedConstraints() != null ? resp.getScopedConstraints() : java.util.Collections.emptyList());
               actionDecision.naLabel = resp.getNaLabel();
               actionDecision.rule = resp.getWinningRuleName();
               actionDecision.priority = resp.getWinningRulePriority();
               actionDecision.finalRule = resp.getWinningRuleFinal();
               actDecisions.put(action, actionDecision);
            }
         }
      }
      return decisions;
   }

   /**
    * Per-action decisions for an identity across the vocabulary this service knows, from the rule engine.
    * {@code useIndex} is accepted for compatibility and ignored: every action is evaluated by the engine,
    * because a precomputed matrix cannot reproduce realm policies, precedence or patterns.
    */
   @POST
   @Path("/fd/evaluate")
   @Authenticated
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalAction("evaluate")
   public Response evaluateFunctionalAccess(@QueryParam("useIndex") @DefaultValue("true") boolean useIndex, EvaluateRequest req) {
      if (req == null || req.identity == null || req.identity.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("identity is required").build();
      }
      // Realm resolution (same as /check)
      String realm = inspectionRealm(req.realm);
      requireInspection(req.identity, realm, req.tenantId, req.roles);
      String applicationId = requestApplicationId(req.applicationId);
      if (applicationId == null) {
         return Response.status(Response.Status.BAD_REQUEST).entity("applicationId is required").build();
      }

      DataDomain dd = requestDataDomain(req.orgRefName, req.accountNumber, req.tenantId, req.dataSegment, req.ownerId, req.identity);
      PrincipalContext pc = evaluationPrincipal(req.identity, evaluationRoles(req.identity, realm, applicationId, req.roles),
              realm, applicationId, dd, req.scope);

      // Discover area->domain->actions the same way as /fd?includeActions=true
      Map<String, Map<String, Set<String>>> discovered = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      // 1) From model discovery
      List<EntityInfo> infoList = getInfoList(true);
      for (EntityInfo ei : infoList) {
         if (ei == null) continue;
         String area = safe(ei.bmFunctionalArea);
         String domain = safe(ei.bmFunctionalDomain);
         if (area.isEmpty() || domain.isEmpty()) continue;
         Set<String> actions = ensureAreaDomain(discovered, area, domain);
         if (ei.actions != null) actions.addAll(ei.actions);
         addDefaultActions(actions);
      }
      // 1.5) REST resources' declared verbs (see /fd) — never widened to CRUD.
      mergeResourceMappings(discovered, discoverResourceMappings(), true);
      // 2) From DB FunctionalDomain collection
      List<FunctionalDomain> stored = functionalDomainRepo.getAllList();
      if (stored != null) {
         for (FunctionalDomain fd : stored) {
            if (fd == null) continue;
            String a = safe(fd.getArea());
            String d = safe(fd.getRefName());
            if (a.isEmpty() || d.isEmpty()) continue;
            Set<String> actions = ensureAreaDomain(discovered, a, d);
            boolean declaredAny = false;
            if (fd.getFunctionalActions() != null) {
               for (com.e2eq.framework.model.security.FunctionalAction fa : fd.getFunctionalActions()) {
                  if (fa == null) continue;
                  String ref = safe(fa.getRefName());
                  if (!ref.isEmpty()) { actions.add(ref); declaredAny = true; }
               }
            }
            if (!declaredAny) addDefaultActions(actions);
         }
      }
      narrow(discovered, req.area, req.functionalDomain, req.action);

      // Parse evalMode and resolve modelClass/resource (parity with /check)
      com.e2eq.framework.model.securityrules.EvalMode mode = com.e2eq.framework.model.securityrules.EvalMode.LEGACY;
      if (req.evalMode != null && !req.evalMode.isBlank()) {
         try {
            mode = com.e2eq.framework.model.securityrules.EvalMode.valueOf(req.evalMode.trim().toUpperCase());
         } catch (IllegalArgumentException ignored) { /* keep LEGACY */ }
      }
      Class<?> mc = null;
      if (req.modelClass != null && !req.modelClass.isBlank()) {
         try {
            mc = Class.forName(req.modelClass.trim());
         } catch (Throwable t) {
            if (io.quarkus.logging.Log.isDebugEnabled()) {
               io.quarkus.logging.Log.debugf(t, "PermissionResource.evaluate: modelClass '%s' could not be resolved; proceeding without", req.modelClass);
            }
            mc = null;
         }
      }
      @SuppressWarnings("unchecked")
      Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> typed = (Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel>) mc;

      Map<String, Map<String, Map<String, EvaluationActionDecision>>> decisions =
              engineDecisions(pc, discovered, realm, applicationId, dd.getOwnerId(), typed, req.resource, mode);

      Map<String, Map<String, List<String>>> allow = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      Map<String, Map<String, List<String>>> deny = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (Map.Entry<String, Map<String, Map<String, EvaluationActionDecision>>> area : decisions.entrySet()) {
         for (Map.Entry<String, Map<String, EvaluationActionDecision>> domain : area.getValue().entrySet()) {
            for (Map.Entry<String, EvaluationActionDecision> action : domain.getValue().entrySet()) {
               Map<String, Map<String, List<String>>> target = "ALLOW".equalsIgnoreCase(action.getValue().effect) ? allow : deny;
               target.computeIfAbsent(area.getKey(), k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER))
                       .computeIfAbsent(domain.getKey(), k -> new ArrayList<>())
                       .add(action.getKey());
            }
         }
      }
      // Sort actions for stable output
      allow.values().forEach(m -> m.values().forEach(list -> list.sort(String::compareToIgnoreCase)));
      deny.values().forEach(m -> m.values().forEach(list -> list.sort(String::compareToIgnoreCase)));

      EvaluationResult res = new EvaluationResult();
      res.allow = allow;
      res.deny = deny;
      res.decisions = decisions;
      res.evalModeUsed = (req.evalMode != null && !req.evalMode.isBlank()) ? req.evalMode.trim().toUpperCase() : com.e2eq.framework.model.securityrules.EvalMode.LEGACY.name();
      return Response.ok(res).build();
   }
}

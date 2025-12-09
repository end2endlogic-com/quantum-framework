package com.e2eq.framework.rest.resources;

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
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.lang.reflect.Method;
import java.util.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

@Path("/system/permissions")
public class PermissionResource {

   @Inject
   @Default
   MorphiaDatastore datastore;

   @Inject
   FunctionalDomainRepo functionalDomainRepo;

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
   public Response roleProvenance(RoleProvenanceRequest req) {
      if (req == null || req.userId == null || req.userId.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("userId is required").build();
      }

      String realm = (req.realm != null && !req.realm.isBlank())
              ? req.realm
              : ruleContext.getDefaultRealm();

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

      // Credential and profile/group exploration for display
      CredentialUserIdPassword cred=null;
      try {
         var ocreds = credentialRepo.findByUserId(req.userId, realm, true);
         if (ocreds.isPresent()) {
            resp.credentialFound = true;
            cred = ocreds.get();
            if (cred.getRoles() != null) resp.credentialRoles = Arrays.asList(cred.getRoles());

            try {
               Optional<UserProfile> userProfileOpt = userProfileRepo.getBySubject(cred.getSubject());
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
         // goto the authProvider and call the getUserGroupsForSubject to get the list of groups the idp has
         // mapped the user to.  This is not a security issue, but it is a way to show the user the groups
         // the idp has mapped the user to.
         if (authProviderFactory.getUserManager() != null && cred != null) {
            List<String> idpGroups = new ArrayList<>(authProviderFactory.getUserManager().getUserRolesForSubject(cred.getSubject()));
            if (idpGroups != null && !idpGroups.isEmpty()) {
               for (String idpGroup : idpGroups) {
                  GroupInfo gi = new GroupInfo();
                  gi.refName = idpGroup;
                  gi.roles = List.of();
                  resp.tokenMappedUserGroups.add(gi);
               }
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

      return Response.ok(resp).build();
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
   public Response entities() {
      return Response.ok(getInfoList()).build();
   }

   @GET
   @Path("/fd")
   @Produces(MediaType.APPLICATION_JSON)
   public Response functionalDomains(@QueryParam("includeActions") @DefaultValue("false") boolean includeActions) {
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
            if (ei.actions != null) actions.addAll(ei.actions);
            addDefaultActions(actions);
         }
      }

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
               if (fd.getFunctionalActions() != null) {
                  for (FunctionalAction a : fd.getFunctionalActions()) {
                     if (a == null) continue;
                     String ref = safe(a.getRefName());
                     if (!ref.isEmpty()) actions.add(ref);
                  }
               }
               addDefaultActions(actions);
            }
         }
      }

      if (!includeActions) {
         // area -> sorted list of domains (union from both sources)
         Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
         for (Map.Entry<String, Map<String, Set<String>>> e : areaDomainActions.entrySet()) {
            List<String> domains = new ArrayList<>(e.getValue().keySet());
            domains.sort(String::compareToIgnoreCase);
            result.put(e.getKey(), domains);
         }
         return Response.ok(result).build();
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
      return Response.ok(out).build();
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


   private String safe(String s) { return (s == null) ? "" : s.trim(); }

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
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response check(CheckRequest req) {
      if (req == null || req.identity == null || req.identity.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("identity is required").build();
      }
      // Prefer explicit realm, then SecurityContext principal realm, then default
      String realm = (req.realm != null && !req.realm.isBlank()) ? req.realm :
              SecurityContext.getPrincipalContext().map(PrincipalContext::getDefaultRealm).orElse(ruleContext.getDefaultRealm());

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
      Set<String> resolvedIdentities = identityRoleResolver.resolveRolesForIdentity(req.identity, realm, securityIdentity);
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
                 identityRoleResolver.resolveRoleSources(req.identity, realm, securityIdentity);

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

   @POST
   @Path("/check-with-index")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response checkWithIndex(CheckRequest req) {
      if (req == null || req.identity == null || req.identity.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("identity is required").build();
      }
      String realm = (req.realm != null && !req.realm.isBlank()) ? req.realm :
              SecurityContext.getPrincipalContext().map(PrincipalContext::getDefaultRealm).orElse(ruleContext.getDefaultRealm());

      // Determine implied identities using centralized resolver. If identity is a role, returned set will just contain it.
      Set<String> identities = identityRoleResolver.resolveRolesForIdentity(req.identity, realm, securityIdentity);
      // Ensure the original identity is included
      identities.add(req.identity);

      // Optional requested data-domain: only if client provided all fields explicitly
      RuleIndexSnapshot idx;
      boolean hasAllDataDomainFields =
              (req.orgRefName != null && !req.orgRefName.isBlank()) &&
              (req.accountNumber != null && !req.accountNumber.isBlank()) &&
              (req.tenantId != null && !req.tenantId.isBlank()) &&
              (req.dataSegment != null) &&
              (req.ownerId != null && !req.ownerId.isBlank());

      if (hasAllDataDomainFields) {
         DataDomain dd = new DataDomain(req.orgRefName, req.accountNumber, req.tenantId, req.dataSegment, req.ownerId);
         idx = ruleContext.exportScopedAccessMatrixForIdentities(identities, dd);
      } else {
         idx = ruleContext.exportScopedAccessMatrixForIdentities(identities, null);
      }
      return Response.ok(idx).build();
   }

   // Lookup outcome in a scoped matrix using wildcard resolution and fallback chain
   private static RuleIndexSnapshot.Outcome lookupOutcome(RuleIndexSnapshot snap, String area, String domain, String action) {
      if (snap == null) return null;
      String startKey = snap.getRequestedScope() != null ? snap.getRequestedScope() : "org=*|acct=*|tenant=*|seg=*|owner=*";
      List<String> fallback = snap.getRequestedFallback() != null ? snap.getRequestedFallback() : List.of("org=*|acct=*|tenant=*|seg=*|owner=*");
      List<String> tryScopes = new ArrayList<>();
      tryScopes.add(startKey);
      tryScopes.addAll(fallback);
      Map<String, RuleIndexSnapshot.ScopedMatrix> scopes = snap.getScopes();
      if (scopes == null) return null;
      for (String key : tryScopes) {
         RuleIndexSnapshot.ScopedMatrix sm = scopes.get(key);
         if (sm == null || sm.isRequiresServer()) continue;
         Map<String, Map<String, Map<String, RuleIndexSnapshot.Outcome>>> m = sm.getMatrix();
         if (m == null) continue;
         RuleIndexSnapshot.Outcome out = lookupInMatrix(m, area, domain, action);
         if (out != null) return out;
      }
      return null;
   }

   private static RuleIndexSnapshot.Outcome lookupInMatrix(Map<String, Map<String, Map<String, RuleIndexSnapshot.Outcome>>> m,
                                                           String area, String domain, String action) {
      String[][] tries = new String[][]{
              {area, domain, action},
              {area, domain, "*"},
              {area, "*", action},
              {area, "*", "*"},
              {"*", domain, action},
              {"*", domain, "*"},
              {"*", "*", action},
              {"*", "*", "*"}
      };
      for (String[] t : tries) {
         Map<String, Map<String, RuleIndexSnapshot.Outcome>> dmap = m.get(t[0]);
         if (dmap == null) continue;
         Map<String, RuleIndexSnapshot.Outcome> amap = dmap.get(t[1]);
         if (amap == null) continue;
         RuleIndexSnapshot.Outcome out = amap.get(t[2]);
         if (out != null) return out;
      }
      return null;
   }

   private static Map<String, Map<String, List<String>>> ensureOutMap(Map<String, Map<String, List<String>>> root, String area, String domain) {
      Map<String, List<String>> dom = root.computeIfAbsent(area, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
      dom.computeIfAbsent(domain, k -> new ArrayList<>());
      return root;
   }

   @POST
   @Path("/fd/evaluate")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response evaluateFunctionalAccess(@QueryParam("useIndex") @DefaultValue("true") boolean useIndex, EvaluateRequest req) {
      if (req == null || req.identity == null || req.identity.isBlank()) {
         return Response.status(Response.Status.BAD_REQUEST).entity("identity is required").build();
      }
      // Realm resolution (same as /check)
      String realm = (req.realm != null && !req.realm.isBlank()) ? req.realm :
              SecurityContext.getPrincipalContext().map(PrincipalContext::getDefaultRealm).orElse(ruleContext.getDefaultRealm());

      // Build DataDomain with fallbacks
      String org = (req.orgRefName != null && !req.orgRefName.isBlank()) ? req.orgRefName :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getOrgRefName()).orElse(securityUtils.getDefaultDataDomain().getOrgRefName());
      String acct = (req.accountNumber != null && !req.accountNumber.isBlank()) ? req.accountNumber :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getAccountNum()).orElse(securityUtils.getDefaultDataDomain().getAccountNum());
      String tenant = (req.tenantId != null && !req.tenantId.isBlank()) ? req.tenantId :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getTenantId()).orElse(securityUtils.getDefaultDataDomain().getTenantId());
      int seg = (req.dataSegment != null) ? req.dataSegment :
              SecurityContext.getPrincipalContext().map(pc -> pc.getDataDomain().getDataSegment()).orElse(securityUtils.getDefaultDataDomain().getDataSegment());
      String ownerId = (req.ownerId != null && !req.ownerId.isBlank()) ? req.ownerId :
              SecurityContext.getResourceContext().map(ResourceContext::getOwnerId)
                      .or(() -> SecurityContext.getPrincipalContext().map(PrincipalContext::getUserId))
                      .orElse(req.identity);
      DataDomain dd = new DataDomain(org, acct, tenant, seg, ownerId);

      // Resolve identities (roles) and include the original identity
      Set<String> identities = identityRoleResolver.resolveRolesForIdentity(req.identity, realm, securityIdentity);
      if (identities == null) identities = new HashSet<>();
      // Remove the userId if present so set holds only roles; but we will still add identity explicitly below for index completeness
      identities.remove(req.identity);
      if (req.roles != null) identities.addAll(Arrays.asList(req.roles));
      identities.add(req.identity);

      // Build PrincipalContext for fallback evaluation when index is disabled or incomplete
      Set<String> roleSetForPc = new HashSet<>(identities);
      roleSetForPc.remove(req.identity);
      String[] rolesArr = roleSetForPc.isEmpty() ? new String[0] : roleSetForPc.toArray(new String[0]);
      PrincipalContext pc = new PrincipalContext.Builder()
              .withDefaultRealm(realm)
              .withDataDomain(dd)
              .withUserId(req.identity)
              .withRoles(rolesArr)
              .withScope(req.scope != null ? req.scope : "api")
              .build();

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
      // 2) From DB FunctionalDomain collection
      List<FunctionalDomain> stored = functionalDomainRepo.getAllList();
      if (stored != null) {
         for (FunctionalDomain fd : stored) {
            if (fd == null) continue;
            String a = safe(fd.getArea());
            String d = safe(fd.getRefName());
            if (a.isEmpty() || d.isEmpty()) continue;
            Set<String> actions = ensureAreaDomain(discovered, a, d);
            if (fd.getFunctionalActions() != null) {
               for (FunctionalAction fa : fd.getFunctionalActions()) {
                  if (fa == null) continue;
                  String ref = safe(fa.getRefName());
                  if (!ref.isEmpty()) actions.add(ref);
               }
            }
            addDefaultActions(actions);
         }
      }

      // Optional filtering
      if (req.area != null && !req.area.isBlank()) {
         discovered.keySet().removeIf(a -> !a.equalsIgnoreCase(req.area));
      }
      if (req.functionalDomain != null && !req.functionalDomain.isBlank()) {
         for (Map.Entry<String, Map<String, Set<String>>> e : discovered.entrySet()) {
            e.getValue().keySet().removeIf(d -> !d.equalsIgnoreCase(req.functionalDomain));
         }
      }
      if (req.action != null && !req.action.isBlank()) {
         for (Map.Entry<String, Map<String, Set<String>>> e : discovered.entrySet()) {
            for (Map.Entry<String, Set<String>> d : e.getValue().entrySet()) {
               d.getValue().removeIf(act -> !act.equalsIgnoreCase(req.action));
            }
         }
      }

      // Export optimized index snapshot for these identities scoped to dd
      RuleIndexSnapshot snap = ruleContext.exportScopedAccessMatrixForIdentities(identities, dd);

      Map<String, Map<String, List<String>>> allow = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      Map<String, Map<String, List<String>>> deny = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      Map<String, Map<String, Map<String, EvaluationActionDecision>>> decisions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

      // Parse evalMode and resolve modelClass/resource (parity with /check)
      com.e2eq.framework.model.securityrules.EvalMode mode = com.e2eq.framework.model.securityrules.EvalMode.LEGACY;
      if (req != null && req.evalMode != null && !req.evalMode.isBlank()) {
         try {
            mode = com.e2eq.framework.model.securityrules.EvalMode.valueOf(req.evalMode.trim().toUpperCase());
         } catch (IllegalArgumentException ignored) { /* keep LEGACY */ }
      }
      Class<?> mc = null;
      if (req != null && req.modelClass != null && !req.modelClass.isBlank()) {
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
      java.util.Map<String, Object> resourceMap = (req != null ? req.resource : null);

      // Classify each discovered action using index first (if enabled), then fall back to server evaluation
      for (Map.Entry<String, Map<String, Set<String>>> areaEntry : discovered.entrySet()) {
         String areaKey = areaEntry.getKey();
         Map<String, Map<String, EvaluationActionDecision>> domDecisions = decisions.computeIfAbsent(areaKey, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
         for (Map.Entry<String, Set<String>> domEntry : areaEntry.getValue().entrySet()) {
            String domainKey = domEntry.getKey();
            Map<String, EvaluationActionDecision> actDecisions = domDecisions.computeIfAbsent(domainKey, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
            for (String action : domEntry.getValue()) {
               Boolean isAllow;
               RuleIndexSnapshot.Outcome out = null;
               if (useIndex && snap != null && snap.isEnabled()) {
                  out = lookupOutcome(snap, areaKey, domainKey, action);
               }
               EvaluationActionDecision actionDecision = new EvaluationActionDecision();
               if (out != null) {
                  isAllow = "ALLOW".equalsIgnoreCase(out.getEffect());
                  actionDecision.effect = isAllow ? "ALLOW" : "DENY";
                  actionDecision.decisionScope = "EXACT"; // index outcome is treated as exact for client purposes
                  actionDecision.rule = out.getRule();
                  actionDecision.priority = out.getPriority();
                  actionDecision.finalRule = out.isFinalRule();
                  actionDecision.source = out.getSource();
               } else {
                  // Fallback to server-side evaluation for exact combination
                  ResourceContext rc = new ResourceContext.Builder()
                          .withRealm(realm)
                          .withArea(areaKey)
                          .withFunctionalDomain(domainKey)
                          .withAction(action)
                          .withOwnerId(ownerId)
                          .build();
                  SecurityCheckResponse resp = ruleContext.checkRules(pc, rc, typed, resourceMap, RuleEffect.DENY, mode);
                  String effect = (resp.getDecision() != null ? resp.getDecision() : (resp.getFinalEffect() != null ? resp.getFinalEffect().name() : "DENY"));
                  isAllow = "ALLOW".equalsIgnoreCase(effect);
                  actionDecision.effect = effect;
                  actionDecision.decisionScope = (resp.getDecisionScope() != null ? resp.getDecisionScope() : "DEFAULT");
                  actionDecision.scopedConstraintsPresent = resp.isScopedConstraintsPresent();
                  actionDecision.scopedConstraints = (resp.getScopedConstraints() != null ? resp.getScopedConstraints() : java.util.Collections.emptyList());
                  actionDecision.naLabel = resp.getNaLabel();
                  // Map winning rule metadata when available (non-index path)
                  actionDecision.rule = resp.getWinningRuleName();
                  actionDecision.priority = resp.getWinningRulePriority();
                  actionDecision.finalRule = resp.getWinningRuleFinal();
                }
               Map<String, Map<String, List<String>>> target = isAllow ? allow : deny;
               Map<String, List<String>> domMap = target.computeIfAbsent(areaKey, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
               List<String> acts = domMap.computeIfAbsent(domainKey, k -> new ArrayList<>());
               acts.add(action);
               actDecisions.put(action, actionDecision);
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
      res.evalModeUsed = (req != null && req.evalMode != null && !req.evalMode.isBlank()) ? req.evalMode.trim().toUpperCase() : com.e2eq.framework.model.securityrules.EvalMode.LEGACY.name();
      return Response.ok(res).build();
   }
}

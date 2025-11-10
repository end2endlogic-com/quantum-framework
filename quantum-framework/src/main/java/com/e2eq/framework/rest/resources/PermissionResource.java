package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.security.IdentityRoleResolver;
import com.e2eq.framework.securityrules.RuleContext;
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
   }

   public static class EvaluationResult {
      // area -> domain -> actions
      public Map<String, Map<String, List<String>>> allow;
      public Map<String, Map<String, List<String>>> deny;
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

      // Do NOT override FunctionalDomain, Area, or Action if scripts supply them; use request or wildcard
      ResourceContext rc = new ResourceContext.Builder()
              .withRealm(realm)
              .withArea(req.area != null ? req.area : "*")
              .withFunctionalDomain(req.functionalDomain != null ? req.functionalDomain : "*")
              .withAction(req.action != null ? req.action : "*")
              .withResourceId(req.resourceId)
              .withOwnerId(ownerId)
              .build();

      SecurityCheckResponse resp = ruleContext.checkRules(pc, rc);
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

      // Classify each discovered action using index first (if enabled), then fall back to server evaluation
      for (Map.Entry<String, Map<String, Set<String>>> areaEntry : discovered.entrySet()) {
         String areaKey = areaEntry.getKey();
         for (Map.Entry<String, Set<String>> domEntry : areaEntry.getValue().entrySet()) {
            String domainKey = domEntry.getKey();
            for (String action : domEntry.getValue()) {
               Boolean isAllow;
               RuleIndexSnapshot.Outcome out = null;
               if (useIndex && snap != null && snap.isEnabled()) {
                  out = lookupOutcome(snap, areaKey, domainKey, action);
               }
               if (out != null) {
                  isAllow = "ALLOW".equalsIgnoreCase(out.getEffect());
               } else {
                  // Fallback to server-side evaluation for exact combination
                  ResourceContext rc = new ResourceContext.Builder()
                          .withRealm(realm)
                          .withArea(areaKey)
                          .withFunctionalDomain(domainKey)
                          .withAction(action)
                          .withOwnerId(ownerId)
                          .build();
                  SecurityCheckResponse resp = ruleContext.checkRules(pc, rc);
                  isAllow = resp.getFinalEffect() == RuleEffect.ALLOW;
               }
               Map<String, Map<String, List<String>>> target = isAllow ? allow : deny;
               Map<String, List<String>> domMap = target.computeIfAbsent(areaKey, k -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
               List<String> acts = domMap.computeIfAbsent(domainKey, k -> new ArrayList<>());
               acts.add(action);
            }
         }
      }

      // Sort actions for stable output
      allow.values().forEach(m -> m.values().forEach(list -> list.sort(String::compareToIgnoreCase)));
      deny.values().forEach(m -> m.values().forEach(list -> list.sort(String::compareToIgnoreCase)));

      EvaluationResult res = new EvaluationResult();
      res.allow = allow;
      res.deny = deny;
      return Response.ok(res).build();
   }
}

package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
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
      List<EntityInfo> infoList = getInfoList(includeActions);
      Map<String, Set<String>> areaToDomains = new HashMap<>();
      // from code (entity info)
      for (EntityInfo ei : infoList) {
         if (ei.bmFunctionalArea == null || ei.bmFunctionalDomain == null) continue;
         areaToDomains.computeIfAbsent(ei.bmFunctionalArea, k -> new HashSet<>()).add(ei.bmFunctionalDomain);
      }
      // augment with entries from the FunctionalDomain collection
      List<FunctionalDomain> stored = functionalDomainRepo.getAllList();
      if (stored != null) {
         for (FunctionalDomain fd : stored) {
            if (fd == null) continue;
            String area = fd.getArea();
            String domainRef = fd.getRefName();
            if (area == null || domainRef == null) continue;
            areaToDomains.computeIfAbsent(area, k -> new HashSet<>()).add(domainRef);

         }
      }

      if (!includeActions) {
         // convert to Map<String, List<String>> for JSON, with sorted lists for consistency
         Map<String, List<String>> result = new HashMap<>();
         for (Map.Entry<String, Set<String>> e : areaToDomains.entrySet()) {
            List<String> domains = new ArrayList<>(e.getValue());
            domains.sort(String::compareToIgnoreCase);
            result.put(e.getKey(), domains);
         }
         return Response.ok(result).build();
      }

      // Build area -> domain -> actions based on EntityInfo.actions
      Map<String, Map<String, Set<String>>> areaDomainActions = new HashMap<>();
      // initialize with known pairs
      for (Map.Entry<String, Set<String>> e : areaToDomains.entrySet()) {
         String area = e.getKey();
         Map<String, Set<String>> dmap = areaDomainActions.computeIfAbsent(area, k -> new HashMap<>());
         for (String d : e.getValue()) dmap.putIfAbsent(d, new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
      }
      // add actions from entity info
      for (EntityInfo ei : infoList) {
         if (ei.bmFunctionalArea == null || ei.bmFunctionalDomain == null) continue;
         Map<String, Set<String>> dmap = areaDomainActions.computeIfAbsent(ei.bmFunctionalArea, k -> new HashMap<>());
         Set<String> actions = dmap.computeIfAbsent(ei.bmFunctionalDomain, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
         if (ei.actions != null) actions.addAll(ei.actions);
         // ensure defaults are present
         actions.add("CREATE");
         actions.add("UPDATE");
         actions.add("DELETE");
         actions.add("VIEW");
         actions.add("LIST");
      }

      // convert to sorted lists
      Map<String, Map<String, List<String>>> out = new HashMap<>();
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

   private Map<String, Map<String, Set<String>>>  scanAndCollectActions(Map<String, Map<String, Set<String>>> areaDomainActions) {
      String basePkg = "com.e2eq.framework.rest.resources";
      Set<Class<?>> classes = listClasses(basePkg);
      for (Class<?> rc : classes) {
         if (rc == null) continue;
         com.e2eq.framework.annotations.FunctionalMapping fm = rc.getAnnotation(com.e2eq.framework.annotations.FunctionalMapping.class);
         jakarta.ws.rs.Path path = rc.getAnnotation(jakarta.ws.rs.Path.class);
         if (fm == null || path == null) continue; // only include resource classes that declare mapping
         String area = safe(fm.area());
         String domain = safe(fm.domain());
         if (area.isEmpty() || domain.isEmpty()) continue;

         Map<String, Set<String>> dmap = areaDomainActions.computeIfAbsent(area, k -> new HashMap<>());
         Set<String> actions = dmap.computeIfAbsent(domain, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));

         for (Method m : rc.getDeclaredMethods()) {
            String action = null;
            com.e2eq.framework.annotations.FunctionalAction fa = m.getAnnotation(com.e2eq.framework.annotations.FunctionalAction.class);
            if (fa != null) {
               String val = fa.value();
               action = (val != null && !val.isBlank()) ? val : m.getName();
               actions.add(action);
               continue;
            }
            // infer from HTTP verb annotations
            if (m.isAnnotationPresent(jakarta.ws.rs.GET.class)) {
               actions.add("VIEW");
               actions.add("LIST");
            }
            if (m.isAnnotationPresent(jakarta.ws.rs.POST.class)) {
               actions.add("CREATE");
            }
            if (m.isAnnotationPresent(jakarta.ws.rs.PUT.class) || m.isAnnotationPresent(jakarta.ws.rs.PATCH.class)) {
               actions.add("UPDATE");
            }
            if (m.isAnnotationPresent(jakarta.ws.rs.DELETE.class)) {
               actions.add("DELETE");
            }
         }
      }
      return areaDomainActions;
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
      String realm = (req.realm != null && !req.realm.isBlank()) ? req.realm : ruleContext.getDefaultRealm();
      // Build DataDomain with fallbacks
      String org = (req.orgRefName != null && !req.orgRefName.isBlank()) ? req.orgRefName : securityUtils.getDefaultDataDomain().getOrgRefName();
      String acct = (req.accountNumber != null && !req.accountNumber.isBlank()) ? req.accountNumber : securityUtils.getDefaultDataDomain().getAccountNum();
      String tenant = (req.tenantId != null && !req.tenantId.isBlank()) ? req.tenantId : securityUtils.getDefaultDataDomain().getTenantId();
      int seg = (req.dataSegment != null) ? req.dataSegment : securityUtils.getDefaultDataDomain().getDataSegment();
      String ownerId = (req.ownerId != null && !req.ownerId.isBlank()) ? req.ownerId : req.identity;

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
      String realm = (req.realm != null && !req.realm.isBlank()) ? req.realm : ruleContext.getDefaultRealm();

      // Determine implied identities using centralized resolver. If identity is a role, returned set will just contain it.
      Set<String> identities = identityRoleResolver.resolveRolesForIdentity(req.identity, realm, securityIdentity);
      // Ensure the original identity is included
      identities.add(req.identity);

      RuleIndexSnapshot idx = ruleContext.exportIndexSnapshotForIdentities(identities);
      return Response.ok(idx).build();
   }
}

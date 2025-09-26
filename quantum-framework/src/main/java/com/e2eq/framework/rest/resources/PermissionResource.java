package com.e2eq.framework.rest.resources;

import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/system/permissions")
public class PermissionResource {

   @Inject
   @Default
   MorphiaDatastore datastore;

   static class EntityInfo {
      public String entity;
      public String bmFunctionalArea;
      public String bmFunctionalDomain;
      EntityInfo(String entity, String area, String domain) {
         this.entity = entity;
         this.bmFunctionalArea = area;
         this.bmFunctionalDomain = domain;
      }
   }

   protected List<EntityInfo> getInfoList() {
      MorphiaDatastore ds = datastore;
      List<EntityModel> entities = ds.getMapper().getMappedEntities();
      List<EntityInfo> infoList = new ArrayList<>();
      for (EntityModel em : entities) {
         String area = null;
         String domain = null;
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
         } catch (Throwable t) {
            // swallow and continue; this endpoint is informational only
         }
         infoList.add(new EntityInfo(em.getName(), area, domain));
      }

      // sort by entity name for consistency
      infoList.sort((a, b) -> a.entity.compareToIgnoreCase(b.entity));
      return infoList;
   }

   @GET
   @Path("/entities")
   @Produces("application/json")
   public Response entities() {
      return Response.ok(getInfoList()).build();
   }

   @GET
   @Path("/fd")
   @Produces("application/json")
   public Response functionalDomains() {
      List<EntityInfo> infoList = getInfoList();
      Map<String, Set<String>> areaToDomains = new HashMap<>();
      for (EntityInfo ei : infoList) {
         if (ei.bmFunctionalArea == null || ei.bmFunctionalDomain == null) continue;
         areaToDomains.computeIfAbsent(ei.bmFunctionalArea, k -> new HashSet<>()).add(ei.bmFunctionalDomain);
      }
      // convert to Map<String, List<String>> for JSON, with sorted lists for consistency
      Map<String, List<String>> result = new HashMap<>();
      for (Map.Entry<String, Set<String>> e : areaToDomains.entrySet()) {
         List<String> domains = new ArrayList<>(e.getValue());
         domains.sort(String::compareToIgnoreCase);
         result.put(e.getKey(), domains);
      }
      return Response.ok(result).build();
   }

}

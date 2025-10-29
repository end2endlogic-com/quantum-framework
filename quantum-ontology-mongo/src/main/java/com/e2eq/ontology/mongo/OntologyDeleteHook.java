package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.morphia.PostDeleteHook;
import com.e2eq.framework.model.persistent.morphia.PreDeleteHook;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.annotations.CascadeType;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Enforces ontology delete-time policies.
 * - beforeDelete: blocks deletion when incoming references exist and policy demands it (BLOCK_IF_REFERENCED)
 * - afterDelete: triggers DELETE cascade for outgoing relations that declare it, and removes residual edges from src
 */
@ApplicationScoped
public class OntologyDeleteHook implements PreDeleteHook, PostDeleteHook {

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    CascadeExecutor cascadeExecutor;

    @Inject
    MorphiaDataStore morphiaDataStore;

    @Override
    public void beforeDelete(String realmId, Object entity) throws RuntimeException {
        if (entity == null) return;
        Class<?> c = entity.getClass();
        OntologyClass oc = c.getAnnotation(OntologyClass.class);
        if (oc == null) return; // not an ontology participant
        String id = idOf(entity);
        // If any incoming edges exist and any property declares BLOCK_IF_REFERENCED, block
        Set<String> blockingPredicates = collectBlockingPredicates();
        List<OntologyEdge> incoming = edgeRepo.findByDst(realmId, id);
        boolean shouldBlock;
        if (blockingPredicates.isEmpty()) {
            // Conservative default: block when there is any incoming edge
            shouldBlock = !incoming.isEmpty();
        } else {
            shouldBlock = incoming.stream().anyMatch(e -> blockingPredicates.contains(e.getP()));
        }
        if (shouldBlock) {
            String refs = incoming.stream().limit(5).map(e -> e.getSrc()+" --"+e.getP()+"--> "+id).toList().toString();
            throw new RuntimeException("Cannot delete entity; it is still referenced: "+refs);
        }
    }

    @Override
    public void afterDelete(String realmId, Class<?> entityClass, String idAsString) {
        try {
            String entityType = classIdOf(entityClass);
            cascadeExecutor.onAfterDelete(realmId, entityType, idAsString);
            // Remove any edges that still have this node as src
            edgeRepo.deleteBySrc(realmId, idAsString, false);
        } catch (Throwable t) {
            Log.warn("OntologyDeleteHook.afterDelete error: "+t.getMessage());
        }
    }

    private String classIdOf(Class<?> clazz) {
        OntologyClass a = clazz.getAnnotation(OntologyClass.class);
        if (a != null && !a.id().isEmpty()) return a.id();
        return clazz.getSimpleName();
    }

    private String idOf(Object entity) {
        try { var m = entity.getClass().getMethod("getRefName"); Object v = m.invoke(entity); if (v!=null) return String.valueOf(v);} catch (Exception ignored) {}
        try { var m = entity.getClass().getMethod("getId"); Object v = m.invoke(entity); if (v!=null) return String.valueOf(v);} catch (Exception ignored) {}
        return String.valueOf(entity.hashCode());
    }

    private Set<String> collectBlockingPredicates() {
        Set<String> out = new HashSet<>();
        try {
            // Discover entity classes via MorphiaOntologyLoader utility
            Datastore ds = morphiaDataStore.getDefaultSystemDataStore();
            dev.morphia.MorphiaDatastore md = (dev.morphia.MorphiaDatastore) ds;
            MorphiaOntologyLoader loader = new MorphiaOntologyLoader(md);
            java.lang.reflect.Method discover = MorphiaOntologyLoader.class.getDeclaredMethod("discoverEntityClasses");
            discover.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Collection<Class<?>> classes = (java.util.Collection<Class<?>>) discover.invoke(loader);
            for (Class<?> c : classes) {
                if (c.getAnnotation(OntologyClass.class) == null) continue;
                for (Field f : c.getDeclaredFields()) {
                    OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
                    if (pa == null) continue;
                    if (hasPolicy(pa, CascadeType.BLOCK_IF_REFERENCED)) {
                        String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? f.getName() : pa.id());
                        out.add(pid);
                    }
                }
                for (Method m : c.getDeclaredMethods()) {
                    OntologyProperty pa = m.getAnnotation(OntologyProperty.class);
                    if (pa == null) continue;
                    if (hasPolicy(pa, CascadeType.BLOCK_IF_REFERENCED)) {
                        String name = m.getName();
                        String derived = (name.startsWith("get") && name.length() > 3) ? Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
                        String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? derived : pa.id());
                        out.add(pid);
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private boolean hasPolicy(OntologyProperty p, CascadeType t) {
        if (p == null || p.cascade() == null) return false;
        for (CascadeType ct : p.cascade()) if (ct == t) return true;
        return false;
    }
}

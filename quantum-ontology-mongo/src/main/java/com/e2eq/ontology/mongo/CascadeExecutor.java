package com.e2eq.ontology.mongo;

import com.e2eq.ontology.annotations.CascadeType;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Executes cascade policies declared on @OntologyProperty.
 *
 * Notes:
 * - UNLINK of edges on update/delete is already handled by OntologyMaterializer pruning.
 * - ORPHAN_REMOVE and DELETE require knowing removed targets or delete events. We best-effort
 *   approximate ORPHAN_REMOVE based on prior edges when available.
 */
@ApplicationScoped
public class CascadeExecutor {

    @Inject
    AnnotatedEdgeExtractor extractor;
    @Inject
    OntologyEdgeRepo edgeRepo;
    @Inject
    MorphiaDataStore morphiaDataStore;

    private final Map<String, Class<?>> ontologyTypeToClass = new HashMap<>();

    @PostConstruct
    void init() {
        try {
            // Reuse MorphiaOntologyLoader logic to discover entity classes
            var ds = morphiaDataStore.getDefaultSystemDataStore();
            dev.morphia.MorphiaDatastore md = (dev.morphia.MorphiaDatastore) ds;
            MorphiaOntologyLoader loader = new MorphiaOntologyLoader(md);
            java.lang.reflect.Method m = MorphiaOntologyLoader.class.getDeclaredMethod("discoverEntityClasses");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Collection<Class<?>> classes = (Collection<Class<?>>) m.invoke(loader);
            for (Class<?> c : classes) {
                OntologyClass oc = c.getAnnotation(OntologyClass.class);
                if (oc != null) {
                    String id = oc.id().isEmpty() ? c.getSimpleName() : oc.id();
                    ontologyTypeToClass.put(id, c);
                }
            }
        } catch (Throwable t) {
            Log.debug("[DEBUG_LOG] CascadeExecutor.init discovery failed: " + t.getMessage());
        }
    }

    public void onAfterPersist(String tenantId, String srcId, Object newEntity, java.util.List<com.e2eq.ontology.model.OntologyEdge> priorExplicit, java.util.List<com.e2eq.ontology.core.Reasoner.Edge> newExplicit) {
        try {
            Class<?> c = newEntity.getClass();
            if (!hasOntologyProperties(c)) return;
            Log.debugf("[DEBUG_LOG] CascadeExecutor.onAfterPersist type=%s tenant=%s src=%s", c.getName(), tenantId, srcId);
            // Build predicate -> refType and ORPHAN_REMOVE policies from annotations
            Map<String, String> predicateToRef = new HashMap<>();
            Set<String> orphanPredicates = new HashSet<>();
            collectPolicies(c, predicateToRef, orphanPredicates);
            if (orphanPredicates.isEmpty()) return;
            // Build maps of prior dsts per predicate (provided) and current dsts from repo after materialization
            Map<String, Set<String>> priorByP = new HashMap<>();
            for (OntologyEdge e : priorExplicit) {
                priorByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
            }
            Map<String, Set<String>> currentByP = new HashMap<>();
            for (OntologyEdge e : edgeRepo.findBySrc(tenantId, srcId)) {
                if (e.isInferred()) continue;
                currentByP.computeIfAbsent(e.getP(), k -> new HashSet<>()).add(e.getDst());
            }
            for (String p : orphanPredicates) {
                Set<String> prev = priorByP.getOrDefault(p, Set.of());
                Set<String> now = currentByP.getOrDefault(p, Set.of());
                if (prev.isEmpty()) continue;
                String refType = predicateToRef.get(p);
                for (String removedDst : prev) {
                    if (now.contains(removedDst)) continue; // still present after materializer
                    // Only remove if no other sources reference it by same predicate
                    List<com.e2eq.ontology.model.OntologyEdge> refs = edgeRepo.findByDstAndP(tenantId, removedDst, p);
                    long otherRefs = refs.stream().filter(ed -> !Objects.equals(ed.getSrc(), srcId)).count();
                    if (otherRefs > 0) continue; // still referenced elsewhere
                    deleteTarget(tenantId, refType, removedDst);
                }
            }
        } catch (Throwable t) {
            Log.warn("CascadeExecutor.onAfterPersist error: " + t.getMessage());
        }
    }

    public void onAfterDelete(String tenantId, String entityType, String entityId) {
        // Future: implement DELETE cascade using registry and repositories.
        Log.debugf("[DEBUG_LOG] CascadeExecutor.onAfterDelete type=%s id=%s tenant=%s", entityType, entityId, tenantId);
        // Intentionally left as a future hook because we don't yet have a delete hook integration point.
    }

    private boolean hasOntologyProperties(Class<?> c) {
        for (Field f : c.getDeclaredFields()) if (f.getAnnotation(OntologyProperty.class) != null) return true;
        for (Method m : c.getDeclaredMethods()) if (m.getAnnotation(OntologyProperty.class) != null) return true;
        return false;
    }

    static boolean hasPolicy(OntologyProperty p, CascadeType t) {
        if (p == null || p.cascade() == null) return false;
        for (CascadeType ct : p.cascade()) if (ct == t) return true;
        return false;
    }

    private void collectPolicies(Class<?> c, Map<String, String> predicateToRef, Set<String> orphanPredicates) {
        for (Field f : c.getDeclaredFields()) {
            OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
            if (pa == null) continue;
            String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? f.getName() : pa.id());
            String ref = !pa.ref().isEmpty() ? pa.ref() : (!pa.range().isEmpty() ? pa.range() : null);
            if (ref != null && !ref.isEmpty()) predicateToRef.put(pid, ref);
            if (hasPolicy(pa, CascadeType.ORPHAN_REMOVE)) orphanPredicates.add(pid);
        }
        for (Method m : c.getDeclaredMethods()) {
            OntologyProperty pa = m.getAnnotation(OntologyProperty.class);
            if (pa == null) continue;
            String name = m.getName();
            String derived = (name.startsWith("get") && name.length() > 3) ? Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
            String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? derived : pa.id());
            String ref = !pa.ref().isEmpty() ? pa.ref() : (!pa.range().isEmpty() ? pa.range() : null);
            if (ref != null && !ref.isEmpty()) predicateToRef.put(pid, ref);
            if (hasPolicy(pa, CascadeType.ORPHAN_REMOVE)) orphanPredicates.add(pid);
        }
    }

    private void deleteTarget(String tenantId, String refType, String targetId) {
        if (refType == null || refType.isEmpty()) return;
        Class<?> clazz = ontologyTypeToClass.get(refType);
        if (clazz == null) {
            Log.debugf("[DEBUG_LOG] CascadeExecutor.deleteTarget unknown refType=%s", refType);
            return;
        }
        try {
            Datastore ds = morphiaDataStore.getDefaultSystemDataStore();
            // Try delete by refName first
            Query<?> q = ds.find(clazz).filter(Filters.eq("refName", targetId));
            long count = q.count();
            if (count == 0) {
                // Try by _id as fallback
                q = ds.find(clazz).filter(Filters.eq("_id", targetId));
            }
            var res = q.delete();
            Log.debugf("[DEBUG_LOG] CascadeExecutor.deleteTarget refType=%s id=%s deleted=%s", refType, targetId, res.getDeletedCount());
        } catch (Throwable t) {
            Log.warn("CascadeExecutor.deleteTarget error: " + t.getMessage());
        }
    }
}

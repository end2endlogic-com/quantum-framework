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
    @Inject
    dev.morphia.MorphiaDatastore morphiaDatastore;

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
        Log.debugf("[DEBUG_LOG] CascadeExecutor.onAfterDelete type=%s id=%s tenant=%s", entityType, entityId, tenantId);
        try {
            Class<?> srcClass = ontologyTypeToClass.getOrDefault(entityType, null);
            if (srcClass == null) return;
            Map<String, CascadeSpec> specs = buildCascadeSpecs(srcClass);
            if (specs.isEmpty()) return;
            Set<String> visited = new HashSet<>();
            recursiveDelete(tenantId, entityType, entityId, specs, visited, 0);
        } catch (Throwable t) {
            Log.warn("CascadeExecutor.onAfterDelete error: " + t.getMessage());
        }
    }

    private void recursiveDelete(String tenantId, String srcType, String srcId, Map<String, CascadeSpec> specs, Set<String> visited, int depth) {
        String key = srcType + "#" + srcId;
        if (!visited.add(key)) return; // cycle guard
        // Gather current outgoing explicit edges from src
        List<OntologyEdge> edges = edgeRepo.findBySrc(tenantId, srcId);
        for (OntologyEdge e : edges) {
            if (e.isInferred()) continue; // only explicit relations drive cascade
            CascadeSpec spec = specs.get(e.getP());
            if (spec == null) continue;
            // depth guard
            if (depth >= spec.depth) continue;
            String targetId = e.getDst();
            String targetType = spec.refType;
            if (targetType == null || targetType.isEmpty()) continue;
            // BLOCK_IF_REFERENCED check
            if (spec.blockIfReferenced) {
                List<OntologyEdge> refs = edgeRepo.findByDstAndP(tenantId, targetId, e.getP());
                boolean referencedElsewhere = refs.stream().anyMatch(ed -> !Objects.equals(ed.getSrc(), srcId));
                if (referencedElsewhere) {
                    Log.debugf("[DEBUG_LOG] Skip DELETE cascade for dst=%s via %s due to other references", targetId, e.getP());
                    continue;
                }
            }
            if (spec.delete) {
                // Delete the target entity and its edges recursively
                deleteTarget(tenantId, targetType, targetId);
                recursiveDelete(tenantId, targetType, targetId, specsFor(targetType), visited, depth + 1);
            }
        }
    }

    private Map<String, CascadeSpec> specsFor(String type) {
        Class<?> c = ontologyTypeToClass.get(type);
        return (c == null) ? Map.of() : buildCascadeSpecs(c);
    }

    private Map<String, CascadeSpec> buildCascadeSpecs(Class<?> c) {
        Map<String, CascadeSpec> map = new HashMap<>();
        for (Field f : c.getDeclaredFields()) {
            OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
            if (pa == null) continue;
            String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? f.getName() : pa.id());
            boolean del = hasPolicy(pa, CascadeType.DELETE);
            boolean blk = hasPolicy(pa, CascadeType.BLOCK_IF_REFERENCED);
            if (!del && !blk) continue;
            String ref = !pa.ref().isEmpty() ? pa.ref() : (!pa.range().isEmpty() ? pa.range() : null);
            int depth = Math.max(0, pa.cascadeDepth());
            map.put(pid, new CascadeSpec(del, blk, depth, ref));
        }
        for (Method m : c.getDeclaredMethods()) {
            OntologyProperty pa = m.getAnnotation(OntologyProperty.class);
            if (pa == null) continue;
            String name = m.getName();
            String derived = (name.startsWith("get") && name.length() > 3) ? Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
            String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? derived : pa.id());
            boolean del = hasPolicy(pa, CascadeType.DELETE);
            boolean blk = hasPolicy(pa, CascadeType.BLOCK_IF_REFERENCED);
            if (!del && !blk) continue;
            String ref = !pa.ref().isEmpty() ? pa.ref() : (!pa.range().isEmpty() ? pa.range() : null);
            int depth = Math.max(0, pa.cascadeDepth());
            map.put(pid, new CascadeSpec(del, blk, depth, ref));
        }
        return map;
    }

    private static final class CascadeSpec {
        final boolean delete; final boolean blockIfReferenced; final int depth; final String refType;
        CascadeSpec(boolean d, boolean b, int dep, String r) { this.delete=d; this.blockIfReferenced=b; this.depth=dep; this.refType=r; }
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
            Datastore ds = morphiaDatastore;
            // Try delete by refName first
            Query<?> q = ds.find(clazz).filter(Filters.eq("refName", targetId));
            long count = q.count();
            if (count == 0) {
                // Try by _id as ObjectId
                try {
                    org.bson.types.ObjectId oid = new org.bson.types.ObjectId(targetId);
                    q = ds.find(clazz).filter(Filters.eq("_id", oid));
                    count = q.count();
                } catch (IllegalArgumentException iae) {
                    // not an ObjectId string; try as raw string
                    q = ds.find(clazz).filter(Filters.eq("_id", targetId));
                    count = q.count();
                }
            }
            var res = q.delete();
            long del = res.getDeletedCount();
            if (del == 0) {
                // Fallback: raw collection delete using @Entity collection name
                dev.morphia.annotations.Entity ent = clazz.getAnnotation(dev.morphia.annotations.Entity.class);
                if (ent != null) {
                    String coll = ent.value();
                    if (coll != null && !coll.isBlank()) {
                        org.bson.Document filter = new org.bson.Document("refName", targetId);
                        var rawRes = ds.getDatabase().getCollection(coll).deleteOne(filter);
                        del = rawRes.getDeletedCount();
                        if (del == 0) {
                            // Try _id as ObjectId or String
                            try {
                                org.bson.types.ObjectId oid = new org.bson.types.ObjectId(targetId);
                                rawRes = ds.getDatabase().getCollection(coll).deleteOne(new org.bson.Document("_id", oid));
                                del = rawRes.getDeletedCount();
                            } catch (IllegalArgumentException iae) {
                                rawRes = ds.getDatabase().getCollection(coll).deleteOne(new org.bson.Document("_id", targetId));
                                del = rawRes.getDeletedCount();
                            }
                        }
                    }
                }
            }
            Log.debugf("[DEBUG_LOG] CascadeExecutor.deleteTarget refType=%s id=%s deleted=%s", refType, targetId, del);
        } catch (Throwable t) {
            Log.warn("CascadeExecutor.deleteTarget error: " + t.getMessage());
        }
    }
}

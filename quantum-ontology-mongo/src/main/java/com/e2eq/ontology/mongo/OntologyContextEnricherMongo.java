
package com.e2eq.ontology.mongo;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.Datastore;
import org.bson.types.ObjectId;
import jakarta.inject.Inject;
import io.quarkus.logging.Log;

@ApplicationScoped
public class OntologyContextEnricherMongo {

    @Inject
    protected OntologyEdgeRepo edgeRepo;

    /**
     * Enriches ontology context for a resource, including resolved display names for destinations.
     * @param tenantId The tenant/realm ID (used for edge filtering, not database selection)
     * @param resourceId The source resource ID
     * @return Map containing edges with optional displayName resolution
     */
    public Map<String, Object> enrich(String tenantId, String resourceId) {
        return enrich(tenantId, resourceId, true);
    }

    /**
     * Enriches ontology context for a resource.
     * @param tenantId The tenant/realm ID (used for edge filtering, not database selection)
     * @param resourceId The source resource ID
     * @param resolveDisplayNames If true, attempts to resolve displayName for each destination entity
     * @return Map containing edges with optional displayName resolution
     */
    public Map<String, Object> enrich(String tenantId, String resourceId, boolean resolveDisplayNames) {
        Map<String, Object> rc = new HashMap<>();
        List<OntologyEdge> edges = edgeRepo.findBySrc(tenantId, resourceId);
        List<Map<String, Object>> edgeSummaries = new ArrayList<>();

        // Group destinations by type for batch resolution
        Map<String, Set<String>> dstIdsByType = new HashMap<>();
        if (resolveDisplayNames) {
            for (OntologyEdge e : edges) {
                if (e.getDstType() != null && e.getDst() != null && isValidObjectId(e.getDst())) {
                    dstIdsByType.computeIfAbsent(e.getDstType(), k -> new HashSet<>()).add(e.getDst());
                }
            }
        }

        // Batch resolve display names by type
        Map<String, String> displayNameCache = new HashMap<>();
        if (resolveDisplayNames && !dstIdsByType.isEmpty()) {
            displayNameCache = resolveDisplayNames(dstIdsByType);
        }

        for (OntologyEdge e : edges) {
            Map<String, Object> edgeMap = new HashMap<>();
            edgeMap.put("p", e.getP());
            edgeMap.put("dst", e.getDst());
            edgeMap.put("dstType", e.getDstType() != null ? e.getDstType() : "");
            edgeMap.put("inferred", e.isInferred());

            // Add resolved display name if available
            String displayName = displayNameCache.get(e.getDst());
            if (displayName != null) {
                edgeMap.put("displayName", displayName);
            }

            edgeSummaries.add(edgeMap);
        }
        rc.put("edges", edgeSummaries);
        return rc;
    }

    /**
     * Resolves display names for a batch of entity IDs grouped by type.
     * Uses the edge repository's datastore (derived from security context) to look up entities.
     */
    private Map<String, String> resolveDisplayNames(Map<String, Set<String>> dstIdsByType) {
        Map<String, String> result = new HashMap<>();
        // Use the edge repo's datastore which is resolved from the security context realm
        Datastore ds = edgeRepo.getMorphiaDataStore();

        for (Map.Entry<String, Set<String>> entry : dstIdsByType.entrySet()) {
            String typeName = entry.getKey();
            Set<String> ids = entry.getValue();

            try {
                // Convert string IDs to ObjectIds
                List<ObjectId> objectIds = new ArrayList<>();
                for (String id : ids) {
                    try {
                        objectIds.add(new ObjectId(id));
                    } catch (IllegalArgumentException ignored) {
                        // Skip invalid ObjectIds
                    }
                }

                if (objectIds.isEmpty()) continue;

                // Query using raw MongoDB to avoid needing to know the exact entity class
                String collectionName = resolveCollectionName(typeName);
                if (collectionName == null) continue;

                var collection = ds.getDatabase().getCollection(collectionName);
                var cursor = collection.find(
                        com.mongodb.client.model.Filters.in("_id", objectIds)
                ).projection(
                        com.mongodb.client.model.Projections.include("_id", "displayName", "refName")
                );

                for (var doc : cursor) {
                    ObjectId docId = doc.getObjectId("_id");
                    String displayName = doc.getString("displayName");
                    if (displayName == null || displayName.isBlank()) {
                        displayName = doc.getString("refName");
                    }
                    if (displayName != null && docId != null) {
                        result.put(docId.toHexString(), displayName);
                    }
                }
            } catch (Exception ex) {
                Log.warnf("Failed to resolve display names for type %s: %s", typeName, ex.getMessage());
            }
        }

        return result;
    }

    /**
     * Maps ontology type names to MongoDB collection names.
     * Override this method to customize type-to-collection mapping.
     */
    protected String resolveCollectionName(String typeName) {
        if (typeName == null) return null;
        // Common convention: type name matches collection name (lowercase first char or as-is)
        // This can be extended with a registry lookup if needed
        return switch (typeName) {
            case "Project" -> "projects";
            case "Customer" -> "customers";
            case "LegalEntity" -> "legalEntities";
            case "Associate" -> "associates";
            case "Timesheet" -> "timesheets";
            case "Receivable" -> "receivables";
            case "UserProfile" -> "userProfiles";
            case "Credential" -> "credentials";
            case "UserGroup" -> "userGroups";
            default -> {
                // Default: lowercase first character
                if (typeName.length() > 1) {
                    yield Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1) + "s";
                }
                yield typeName.toLowerCase() + "s";
            }
        };
    }

    private boolean isValidObjectId(String id) {
        if (id == null || id.length() != 24) return false;
        return id.matches("^[0-9a-fA-F]{24}$");
    }
}

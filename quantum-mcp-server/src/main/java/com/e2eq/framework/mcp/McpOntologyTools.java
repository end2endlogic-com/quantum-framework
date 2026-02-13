package com.e2eq.framework.mcp;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.*;

/**
 * Exposes ontology relationship and predicate discovery as MCP tools.
 *
 * <p>These tools allow MCP clients to explore the ontology graph:
 * find outgoing/incoming relationships for an entity, and discover
 * what predicates (relationship types) are defined in the ontology.</p>
 *
 * @see OntologyEdgeRepo
 * @see TenantOntologyRegistryProvider
 */
public class McpOntologyTools {

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Find ontology relationships (edges) for an entity. "
            + "Returns outgoing edges (entity -> target), incoming edges (source -> entity), or both. "
            + "Each edge includes: predicate, source/destination entity ID and type, and whether it is inferred or derived.")
    String query_relationships(
            @ToolArg(description = "The entity ID to find relationships for (e.g. the refName or ObjectId hex string of the entity)") String entityId,
            @ToolArg(description = "Direction: 'out' for outgoing edges FROM this entity, 'in' for incoming edges TO this entity, 'both' for all. Default: 'both'") String direction,
            @ToolArg(description = "Optional predicate filter - only return edges with this predicate (e.g. 'placedInOrg', 'canSeeLocation')") String predicate,
            @ToolArg(description = "Optional tenant realm. When omitted, derived from the caller's security context.") String realm) {
        try {
            DataDomain dataDomain = resolveDataDomain(realm);
            if (dataDomain == null) {
                return "{\"error\":\"NoDataDomain\",\"message\":\"Cannot resolve DataDomain from security context. Provide a realm parameter.\"}";
            }

            String dir = (direction != null && !direction.isBlank()) ? direction.toLowerCase() : "both";
            List<Map<String, Object>> edges = new ArrayList<>();

            if ("out".equals(dir) || "both".equals(dir)) {
                List<OntologyEdge> outgoing;
                if (predicate != null && !predicate.isBlank()) {
                    outgoing = edgeRepo.findBySrcAndP(dataDomain, entityId, predicate);
                } else {
                    outgoing = edgeRepo.findBySrc(dataDomain, entityId);
                }
                for (OntologyEdge e : outgoing) {
                    edges.add(edgeToMap(e, "outgoing"));
                }
            }

            if ("in".equals(dir) || "both".equals(dir)) {
                List<OntologyEdge> incoming;
                if (predicate != null && !predicate.isBlank()) {
                    incoming = edgeRepo.findByDstAndP(dataDomain, entityId, predicate);
                } else {
                    incoming = edgeRepo.findByDst(dataDomain, entityId);
                }
                for (OntologyEdge e : incoming) {
                    edges.add(edgeToMap(e, "incoming"));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("entityId", entityId);
            result.put("direction", dir);
            result.put("count", edges.size());
            result.put("edges", edges);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"RelationshipQueryFailed\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "List ontology predicates (relationship types) defined in the TBox schema. "
            + "Returns predicate name, domain (source type), range (target type), and characteristics "
            + "(transitive, symmetric, functional, inferred). Use this to discover what relationship types exist before querying edges.")
    String query_predicates(
            @ToolArg(description = "Optional filter: only return predicates where this is the domain (source entity type), e.g. 'Associate'") String domainFilter,
            @ToolArg(description = "Optional filter: only return predicates where this is the range (target entity type), e.g. 'Location'") String rangeFilter,
            @ToolArg(description = "Optional tenant realm. When omitted, derived from the caller's security context.") String realm) {
        try {
            String resolvedRealm = resolveRealm(realm);
            OntologyRegistry registry = registryProvider.getRegistryForRealm(resolvedRealm);
            if (registry == null) {
                return "{\"error\":\"NoRegistry\",\"message\":\"No ontology registry found for realm: " + resolvedRealm + "\"}";
            }

            Map<String, OntologyRegistry.PropertyDef> props = registry.properties();
            List<Map<String, Object>> predicates = new ArrayList<>();

            for (OntologyRegistry.PropertyDef p : props.values()) {
                // Apply domain filter
                if (domainFilter != null && !domainFilter.isBlank()) {
                    if (p.domain().isEmpty() || !p.domain().get().equalsIgnoreCase(domainFilter)) {
                        continue;
                    }
                }
                // Apply range filter
                if (rangeFilter != null && !rangeFilter.isBlank()) {
                    if (p.range().isEmpty() || !p.range().get().equalsIgnoreCase(rangeFilter)) {
                        continue;
                    }
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", p.name());
                entry.put("domain", p.domain().orElse(null));
                entry.put("range", p.range().orElse(null));
                entry.put("transitive", p.transitive());
                entry.put("symmetric", p.symmetric());
                entry.put("functional", p.functional());
                entry.put("inferred", p.inferred());
                if (p.inverseOf().isPresent()) {
                    entry.put("inverseOf", p.inverseOf().get());
                }
                if (p.subPropertyOf() != null && !p.subPropertyOf().isEmpty()) {
                    entry.put("subPropertyOf", p.subPropertyOf());
                }
                predicates.add(entry);
            }

            // Sort by name
            predicates.sort(Comparator.comparing(m -> (String) m.get("name")));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", predicates.size());
            result.put("predicates", predicates);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"PredicateQueryFailed\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private Map<String, Object> edgeToMap(OntologyEdge e, String direction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direction", direction);
        m.put("predicate", e.getP());
        m.put("src", e.getSrc());
        m.put("srcType", e.getSrcType());
        m.put("dst", e.getDst());
        m.put("dstType", e.getDstType());
        m.put("inferred", e.isInferred());
        m.put("derived", e.isDerived());
        if (e.getTs() != null) {
            m.put("timestamp", e.getTs().toInstant().toString());
        }
        return m;
    }

    private DataDomain resolveDataDomain(String realm) {
        // Try SecurityContext first
        DataDomain dd = SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getDataDomain)
                .orElse(null);
        if (dd != null) {
            return dd;
        }
        // Fallback: build minimal DataDomain from realm
        if (realm != null && !realm.isBlank()) {
            dd = new DataDomain();
            dd.setOrgRefName("system");
            dd.setAccountNum("0000000000");
            dd.setTenantId(realm.replace('-', '.'));
            dd.setOwnerId("system");
            dd.setDataSegment(0);
            return dd;
        }
        return null;
    }

    private String resolveRealm(String requestRealm) {
        if (requestRealm != null && !requestRealm.isBlank()) {
            return requestRealm;
        }
        return SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getDefaultRealm)
                .orElse("defaultRealm");
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

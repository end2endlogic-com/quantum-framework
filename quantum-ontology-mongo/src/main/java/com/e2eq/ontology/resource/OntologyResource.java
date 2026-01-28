package com.e2eq.ontology.resource;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * REST API for Ontology TBox and ABox operations.
 * <p>
 * This resource is realm-aware. The OntologyRegistry is resolved based on
 * the current SecurityContext's realm. For ABox queries, DataDomain is used
 * for row-level filtering.
 * </p>
 */
@ApplicationScoped
@Path("ontology")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"user", "admin", "system"})
@FunctionalMapping(area="ONTOLOGY", domain="INDEXES")
public class OntologyResource {

    @Inject
    OntologyRegistry registry;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @Inject
    Executor managedExecutor;

    @GET
    @Path("registry")
    @Operation(summary = "Get full TBox")
    @SecurityRequirement(name = "bearerAuth")
    public Response getTBox() {
        Map<String, ClassDef> classes = registry.classes();
        Map<String, PropertyDef> props = registry.properties();
        List<PropertyChainDef> chains = registry.propertyChains();
        var tbox = new TBox(classes, props, chains);
        return Response.ok(tbox).build();
    }

    @GET
    @Path("classes/{name}")
    @Operation(summary = "Get a class by name")
    @SecurityRequirement(name = "bearerAuth")
    public Response getClass(@PathParam("name") String name) {
        return registry.classOf(name)
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("classes")
    @Operation(summary = "List classes (best-effort)")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("LIST_CLASSES")
    public Response listClasses(@QueryParam("contains") String contains) {
        Set<String> classNames = new HashSet<>();
        registry.properties().values().forEach(p -> {
            p.domain().ifPresent(classNames::add);
            p.range().ifPresent(classNames::add);
        });
        List<ClassDef> defs = classNames.stream()
                .filter(n -> contains == null || n.contains(contains))
                .map(n -> registry.classOf(n).orElse(new ClassDef(n, Set.of(), Set.of(), Set.of())))
                .sorted(Comparator.comparing(ClassDef::name))
                .toList();
        return Response.ok(defs).build();
    }

    @GET
    @Path("properties")
    @Operation(summary = "List properties")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("LIST_PROPERTIES")
    public Response listProperties() {
        return Response.ok(registry.properties().values().stream()
                .sorted(Comparator.comparing(PropertyDef::name))
                .toList()).build();
    }

    @GET
    @Path("properties/{name}")
    @Operation(summary = "Get a property by name")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("GET_PROPERTIES")
    public Response getProperty(@PathParam("name") String name) {
        return registry.propertyOf(name)
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("propertyChains")
    @Operation(summary = "List property chains")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("LIST_PROPERTY_CHAINS")
    public Response listPropertyChains() {
        return Response.ok(registry.propertyChains()).build();
    }

    /**
     * Trigger full ontology reindex with Server-Sent Events for real-time progress streaming.
     * This is the preferred method for reindexing as it provides real-time feedback.
     *
     * @param realm the realm to reindex
     * @param force if true, purge derived edges before reindexing
     * @param eventSink SSE event sink for streaming progress
     * @param sse SSE factory
     */
    @POST
    @Path("reindex/stream")
    @RolesAllowed({"admin", "system"})
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(summary = "Trigger full ontology reindex with SSE progress streaming")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("RE-INDEX")
    public void triggerReindexStream(@QueryParam("realm") @DefaultValue("default") String realm,
                                     @QueryParam("force") @DefaultValue("false") boolean force,
                                     @Context SseEventSink eventSink,
                                     @Context Sse sse) {
        Multi.createFrom().emitter(emitter -> {
            try {
                com.e2eq.ontology.service.OntologyReindexer reindexer =
                        io.quarkus.arc.Arc.container().instance(com.e2eq.ontology.service.OntologyReindexer.class).get();
                reindexer.runAsync(realm, force, emitter);
            } catch (Throwable t) {
                emitter.fail(t);
            }
        }).emitOn(managedExecutor)
          .subscribe().with(
              message -> {
                  if (!eventSink.isClosed()) {
                      eventSink.send(sse.newEvent((String) message));
                  }
              },
              failure -> {
                  if (!eventSink.isClosed()) {
                      eventSink.send(sse.newEvent("Error: " + failure.getMessage()));
                      eventSink.close();
                  }
              },
              () -> {
                  if (!eventSink.isClosed()) {
                      eventSink.send(sse.newEvent("Reindex task completed"));
                      eventSink.close();
                  }
              }
          );
    }

    /**
     * Trigger full ontology reindex (legacy endpoint - returns immediately).
     * For real-time progress, use POST /reindex/stream instead.
     *
     * @param realm the realm to reindex
     * @param force if true, purge derived edges before reindexing
     * @return accepted response with current status
     */
    @POST
    @Path("reindex")
    @RolesAllowed({"admin", "system"})
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Trigger full ontology reindex (returns immediately, use /reindex/stream for real-time progress)")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("RE-INDEX")
    public Response triggerReindex(@QueryParam("realm") @DefaultValue("default") String realm,
                                   @QueryParam("force") @DefaultValue("false") boolean force) {
        try {
            // Delegate to service
            com.e2eq.ontology.service.OntologyReindexer reindexer = io.quarkus.arc.Arc.container().instance(com.e2eq.ontology.service.OntologyReindexer.class).get();
            reindexer.runAsync(realm, force);
            return Response.accepted(Map.of(
                    "message", "Reindex started. Use GET /ontology/reindex/status to poll for progress, or use POST /ontology/reindex/stream for real-time SSE updates.",
                    "status", reindexer.status(),
                    "realm", realm,
                    "force", force
            )).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("reindex/status")
    @RolesAllowed({"admin", "system"})
    @Operation(summary = "Get the status and results of the last/current reindex")
    @SecurityRequirement(name = "bearerAuth")
    public Response getReindexStatus() {
        try {
            com.e2eq.ontology.service.OntologyReindexer reindexer = io.quarkus.arc.Arc.container().instance(com.e2eq.ontology.service.OntologyReindexer.class).get();
            return Response.ok(reindexer.getResult()).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("version")
    @Operation(summary = "Get current ontology YAML version metadata")
    @SecurityRequirement(name = "bearerAuth")
    public Response getVersion() {
        try {
            com.e2eq.ontology.repo.OntologyMetaRepo metaRepo = io.quarkus.arc.Arc.container().instance(com.e2eq.ontology.repo.OntologyMetaRepo.class).get();
            var metaOpt = metaRepo.getSingleton();
            Map<String, Object> body = metaOpt.<Map<String, Object>>map(m -> Map.of(
                            "yamlHash", m.getYamlHash(),
                            "tboxHash", m.getTboxHash(),
                            "appliedAt", m.getAppliedAt()))
                    .orElse(Map.of("yamlHash", null, "tboxHash", null, "appliedAt", null));
            return Response.ok(body).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("hash")
    @Operation(summary = "Get current TBox hash")
    @SecurityRequirement(name = "bearerAuth")
    public Response getTBoxHash() {
        String hash = registry.getTBoxHash();
        return Response.ok(Map.of("tboxHash", hash)).build();
    }

    @GET
    @Path("realm/status")
    @Operation(summary = "Get realm ontology status including cached realms")
    @SecurityRequirement(name = "bearerAuth")
    @RolesAllowed({"admin", "system"})
    public Response getRealmStatus() {
        Set<String> cachedRealms = registryProvider.getCachedRealms();
        Map<String, Object> body = Map.of(
                "cachedRealms", cachedRealms,
                "cacheSize", cachedRealms.size()
        );
        return Response.ok(body).build();
    }

    @POST
    @Path("realm/invalidate")
    @Operation(summary = "Invalidate the ontology cache for current or specified realm")
    @SecurityRequirement(name = "bearerAuth")
    @RolesAllowed({"admin", "system"})
    @Consumes(MediaType.WILDCARD)
    public Response invalidateRealm(@QueryParam("realm") String realm) {
        if (realm != null && !realm.isBlank()) {
            registryProvider.invalidateRealm(realm);
            return Response.ok(Map.of("invalidated", realm, "success", true)).build();
        } else {
            // Invalidate current realm from security context
            registryProvider.clearCache();
            return Response.ok(Map.of("invalidated", "all", "success", true)).build();
        }
    }

    @POST
    @Path("realm/rebuild")
    @Operation(summary = "Force rebuild the TBox for a realm")
    @SecurityRequirement(name = "bearerAuth")
    @RolesAllowed({"admin", "system"})
    @Consumes(MediaType.WILDCARD)
    public Response forceRebuild(@QueryParam("realm") String realm) {
        if (realm == null || realm.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "realm parameter is required"))
                    .build();
        }
        try {
            OntologyRegistry rebuilt = registryProvider.forceRebuild(realm);
            Map<String, Object> body = Map.of(
                    "realm", realm,
                    "success", true,
                    "classes", rebuilt.classes().size(),
                    "properties", rebuilt.properties().size(),
                    "tboxHash", rebuilt.getTBoxHash()
            );
            return Response.ok(body).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("properties/{name}/superProperties")
    @Operation(summary = "Get super-properties of a property")
    @SecurityRequirement(name = "bearerAuth")
    public Response getSuperProperties(@PathParam("name") String name) {
        Set<String> supers = registry.superPropertiesOf(name);
        return Response.ok(Map.of("property", name, "superProperties", supers)).build();
    }

    @GET
    @Path("properties/{name}/subProperties")
    @Operation(summary = "Get sub-properties of a property")
    @SecurityRequirement(name = "bearerAuth")
    public Response getSubProperties(@PathParam("name") String name) {
        Set<String> subs = registry.subPropertiesOf(name);
        return Response.ok(Map.of("property", name, "subProperties", subs)).build();
    }

    @GET
    @Path("properties/{name}/inverse")
    @Operation(summary = "Get inverse property")
    @SecurityRequirement(name = "bearerAuth")
    public Response getInverseProperty(@PathParam("name") String name) {
        Optional<String> inv = registry.inverseOf(name);
        return Response.ok(Map.of("property", name, "inverse", inv.orElse(null))).build();
    }

    @GET
    @Path("classes/{name}/ancestors")
    @Operation(summary = "Get ancestor classes")
    @SecurityRequirement(name = "bearerAuth")
    public Response getAncestors(@PathParam("name") String name) {
        Set<String> ancestors = registry.ancestorsOf(name);
        return Response.ok(Map.of("class", name, "ancestors", ancestors)).build();
    }

    @GET
    @Path("classes/{name}/descendants")
    @Operation(summary = "Get descendant classes")
    @SecurityRequirement(name = "bearerAuth")
    public Response getDescendants(@PathParam("name") String name) {
        Set<String> descendants = registry.descendantsOf(name);
        return Response.ok(Map.of("class", name, "descendants", descendants)).build();
    }

    @GET
    @Path("graph/jointjs")
    @Operation(summary = "Graph view as JointJS cells[]")
    @SecurityRequirement(name = "bearerAuth")
    public Response graphJointJS(@QueryParam("focus") String focus,
                                 @QueryParam("depth") @DefaultValue("1") int depth,
                                 @QueryParam("include") @DefaultValue("classes,properties,chains") String includeCsv) {
        Set<String> include = Arrays.stream(includeCsv.split(","))
                .map(String::trim).map(String::toLowerCase).collect(Collectors.toSet());

        GraphBuilder b = new GraphBuilder(registry);
        var cells = b.buildJointJSCELLS(focus, depth, include);
        Map<String, Object> payload = Map.of("cells", cells);
        return Response.ok(payload).build();
    }

    @GET
    @Path("summary")
    @Operation(summary = "Small summary counts for UI")
    @SecurityRequirement(name = "bearerAuth")
    public Response summary() {
        int propertyCount = registry.properties().size();
        Set<String> classNames = new HashSet<>();
        registry.properties().values().forEach(p -> { p.domain().ifPresent(classNames::add); p.range().ifPresent(classNames::add); });
        return Response.ok(Map.of("classes", classNames.size(), "properties", propertyCount, "chains", registry.propertyChains().size())).build();
    }

    // ========================================================================
    // ABox Edge APIs - Query edges stored in the system
    // ========================================================================

    /**
     * DTO for edge representation in API responses.
     */
    public record EdgeDTO(
            String src,
            String srcType,
            String predicate,
            String dst,
            String dstType,
            boolean inferred,
            boolean derived,
            Map<String, Object> provenance,
            String origin // "explicit", "inferred", "computed"
    ) {
        public static EdgeDTO from(OntologyEdge e) {
            String origin;
            if (e.isDerived() && !e.isInferred()) {
                origin = "computed";
            } else if (e.isInferred()) {
                origin = "inferred";
            } else {
                origin = "explicit";
            }
            return new EdgeDTO(
                    e.getSrc(), e.getSrcType(), e.getP(), e.getDst(), e.getDstType(),
                    e.isInferred(), e.isDerived(), e.getProv(), origin
            );
        }
    }

    @GET
    @Path("edges")
    @Operation(summary = "List all edges in the system, optionally filtered by predicate, source type, or destination type")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("LIST_EDGES")
    public Response listEdges(@Context HttpHeaders headers,
                              @QueryParam("predicate") String predicate,
                              @QueryParam("srcType") String srcType,
                              @QueryParam("dstType") String dstType,
                              @QueryParam("inferred") Boolean inferred,
                              @QueryParam("derived") Boolean derived,
                              @QueryParam("limit") @DefaultValue("1000") int limit) {
        DataDomain dataDomain = getDataDomainFromContext(headers);
        if (dataDomain == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No DataDomain available from security context"))
                    .build();
        }

        // Query edges with optional predicate filter
        List<OntologyEdge> edges;
        if (predicate != null && !predicate.isBlank()) {
            edges = edgeRepo.findByProperty(dataDomain, predicate);
        } else {
            // For no predicate filter, we need to query all edges (potentially expensive)
            // Return error suggesting to use a filter
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Please provide at least a 'predicate' filter to query edges"))
                    .build();
        }

        // Apply additional filters
        List<EdgeDTO> result = edges.stream()
                .filter(e -> srcType == null || srcType.equals(e.getSrcType()))
                .filter(e -> dstType == null || dstType.equals(e.getDstType()))
                .filter(e -> inferred == null || inferred.equals(e.isInferred()))
                .filter(e -> derived == null || derived.equals(e.isDerived()))
                .limit(limit)
                .map(EdgeDTO::from)
                .toList();

        return Response.ok(Map.of(
                "edges", result,
                "count", result.size(),
                "truncated", result.size() == limit
        )).build();
    }

    @GET
    @Path("instance/{type}/{id}/edges")
    @Operation(summary = "Get all ontology edges for a specific class instance")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("GET_INSTANCE_EDGES")
    public Response getInstanceEdges(@Context HttpHeaders headers,
                                     @PathParam("type") String type,
                                     @PathParam("id") String id,
                                     @QueryParam("direction") @DefaultValue("both") String direction,
                                     @QueryParam("predicate") List<String> predicates) {
        if (type == null || type.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "type path parameter is required"))
                    .build();
        }
        if (id == null || id.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "id path parameter is required"))
                    .build();
        }

        DataDomain dataDomain = getDataDomainFromContext(headers);
        if (dataDomain == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No DataDomain available from security context"))
                    .build();
        }

        String dir = direction.toLowerCase(Locale.ROOT);
        boolean includeOutgoing = dir.equals("out") || dir.equals("both");
        boolean includeIncoming = dir.equals("in") || dir.equals("both");
        Set<String> predicateFilter = predicates != null && !predicates.isEmpty()
                ? predicates.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet())
                : null;

        List<EdgeDTO> outgoing = new ArrayList<>();
        List<EdgeDTO> incoming = new ArrayList<>();

        if (includeOutgoing) {
            for (OntologyEdge e : edgeRepo.findBySrc(dataDomain, id)) {
                if (!type.equals(e.getSrcType())) continue;
                if (predicateFilter != null && !predicateFilter.contains(e.getP())) continue;
                outgoing.add(EdgeDTO.from(e));
            }
        }

        if (includeIncoming) {
            for (OntologyEdge e : edgeRepo.findByDst(dataDomain, id)) {
                if (!type.equals(e.getDstType())) continue;
                if (predicateFilter != null && !predicateFilter.contains(e.getP())) continue;
                incoming.add(EdgeDTO.from(e));
            }
        }

        // Group edges by origin type for summary
        Map<String, Long> outgoingByOrigin = outgoing.stream()
                .collect(Collectors.groupingBy(EdgeDTO::origin, Collectors.counting()));
        Map<String, Long> incomingByOrigin = incoming.stream()
                .collect(Collectors.groupingBy(EdgeDTO::origin, Collectors.counting()));

        return Response.ok(Map.of(
                "instanceType", type,
                "instanceId", id,
                "outgoing", outgoing,
                "incoming", incoming,
                "summary", Map.of(
                        "totalOutgoing", outgoing.size(),
                        "totalIncoming", incoming.size(),
                        "outgoingByOrigin", outgoingByOrigin,
                        "incomingByOrigin", incomingByOrigin
                )
        )).build();
    }

    // ========================================================================
    // TBox Edge Definition APIs - Show what edges SHOULD exist per ontology
    // ========================================================================

    /**
     * DTO representing an edge definition from the TBox (ontology schema).
     */
    public record TBoxEdgeDefinition(
            String predicate,
            String domain,       // Source type (class)
            String range,        // Destination type (class)
            boolean functional,  // At most one value?
            boolean transitive,  // Transitive property?
            boolean symmetric,   // Symmetric property?
            boolean inferred,    // Marked as inferred in TBox?
            Set<String> subPropertyOf,   // Super-properties
            String inverseOf,    // Inverse property name
            String impliedBy     // Chain that implies this (if any)
    ) {}

    @GET
    @Path("tbox/edges")
    @Operation(summary = "Get all edge definitions from the TBox (what edges SHOULD exist based on ontology)")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("GET_TBOX_EDGES")
    public Response getTBoxEdges(@QueryParam("domain") String domain,
                                 @QueryParam("range") String range,
                                 @QueryParam("includeImplied") @DefaultValue("true") boolean includeImplied) {
        List<TBoxEdgeDefinition> definitions = new ArrayList<>();

        // Collect properties implied by chains
        Map<String, List<String>> impliedByChains = new HashMap<>();
        for (var chain : registry.propertyChains()) {
            if (chain.implies() != null && !chain.implies().isBlank()) {
                impliedByChains.computeIfAbsent(chain.implies(), k -> new ArrayList<>())
                        .add(String.join(" → ", chain.chain()));
            }
        }

        // Build definitions from properties
        for (var prop : registry.properties().values()) {
            // Filter by domain/range if specified
            if (domain != null && !domain.isBlank() && prop.domain().map(d -> !d.equals(domain)).orElse(true)) {
                continue;
            }
            if (range != null && !range.isBlank() && prop.range().map(r -> !r.equals(range)).orElse(true)) {
                continue;
            }

            // Skip inferred-only properties if includeImplied is false
            boolean isImplied = impliedByChains.containsKey(prop.name()) || prop.inferred();
            if (!includeImplied && isImplied) {
                continue;
            }

            List<String> chainImpliers = impliedByChains.get(prop.name());
            String impliedBy = chainImpliers != null ? String.join("; ", chainImpliers) : null;

            definitions.add(new TBoxEdgeDefinition(
                    prop.name(),
                    prop.domain().orElse(null),
                    prop.range().orElse(null),
                    prop.functional(),
                    prop.transitive(),
                    prop.symmetric(),
                    prop.inferred() || impliedByChains.containsKey(prop.name()),
                    prop.subPropertyOf(),
                    prop.inverseOf().orElse(null),
                    impliedBy
            ));
        }

        // Sort by predicate name
        definitions.sort(Comparator.comparing(TBoxEdgeDefinition::predicate));

        // Summary statistics
        long explicitCount = definitions.stream().filter(d -> !d.inferred()).count();
        long inferredCount = definitions.stream().filter(TBoxEdgeDefinition::inferred).count();
        long transitiveCount = definitions.stream().filter(TBoxEdgeDefinition::transitive).count();
        long functionalCount = definitions.stream().filter(TBoxEdgeDefinition::functional).count();
        long symmetricCount = definitions.stream().filter(TBoxEdgeDefinition::symmetric).count();

        return Response.ok(Map.of(
                "definitions", definitions,
                "summary", Map.of(
                        "total", definitions.size(),
                        "explicit", explicitCount,
                        "inferred", inferredCount,
                        "transitive", transitiveCount,
                        "functional", functionalCount,
                        "symmetric", symmetricCount
                ),
                "tboxHash", registry.getTBoxHash()
        )).build();
    }

    @GET
    @Path("tbox/edges/{predicate}")
    @Operation(summary = "Get detailed edge definition for a specific predicate including provenance rules")
    @SecurityRequirement(name = "bearerAuth")
    @FunctionalAction("GET_TBOX_EDGE")
    public Response getTBoxEdge(@PathParam("predicate") String predicate) {
        var propOpt = registry.propertyOf(predicate);
        if (propOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Property not found: " + predicate))
                    .build();
        }

        var prop = propOpt.get();

        // Find chains that imply this property
        List<Map<String, Object>> implyingChains = new ArrayList<>();
        for (var chain : registry.propertyChains()) {
            if (predicate.equals(chain.implies())) {
                implyingChains.add(Map.of(
                        "chain", chain.chain(),
                        "description", String.join(" → ", chain.chain()) + " ⇒ " + predicate
                ));
            }
        }

        // Find chains that use this property
        List<Map<String, Object>> chainsUsing = new ArrayList<>();
        for (var chain : registry.propertyChains()) {
            if (chain.chain().contains(predicate)) {
                chainsUsing.add(Map.of(
                        "chain", chain.chain(),
                        "implies", chain.implies(),
                        "description", String.join(" → ", chain.chain()) + " ⇒ " + chain.implies()
                ));
            }
        }

        // Get hierarchy information
        Set<String> superProps = registry.superPropertiesOf(predicate);
        Set<String> subProps = registry.subPropertiesOf(predicate);
        Optional<String> inverse = registry.inverseOf(predicate);

        return Response.ok(Map.of(
                "predicate", predicate,
                "domain", prop.domain().orElse(null),
                "range", prop.range().orElse(null),
                "characteristics", Map.of(
                        "functional", prop.functional(),
                        "transitive", prop.transitive(),
                        "symmetric", prop.symmetric(),
                        "inferred", prop.inferred()
                ),
                "hierarchy", Map.of(
                        "superProperties", superProps,
                        "subProperties", subProps,
                        "inverseOf", inverse.orElse(null)
                ),
                "inference", Map.of(
                        "impliedByChains", implyingChains,
                        "usedInChains", chainsUsing,
                        "willCreateInferredEdges", !implyingChains.isEmpty() || prop.transitive() || inverse.isPresent() || prop.symmetric()
                )
        )).build();
    }

    /**
     * Helper method to extract DataDomain from security context or headers.
     */
    private DataDomain getDataDomainFromContext(HttpHeaders headers) {
        DataDomain dataDomain = SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getDataDomain)
                .orElse(null);

        if (dataDomain == null) {
            // Fallback: try to construct from X-Realm header for backward compatibility
            String tenant = headers.getHeaderString("X-Realm");
            if (tenant != null && !tenant.isBlank()) {
                dataDomain = new DataDomain();
                dataDomain.setOrgRefName("ontology");
                dataDomain.setAccountNum("0000000000");
                dataDomain.setTenantId(tenant);
                dataDomain.setOwnerId("system");
                dataDomain.setDataSegment(0);
            }
        }
        return dataDomain;
    }

    @GET
    @Path("graph/instances/jointjs")
    @Operation(summary = "Instance graph as JointJS cells[] given a src id and type")
    @SecurityRequirement(name = "bearerAuth")
    public Response instanceGraphJointJS(@Context HttpHeaders headers,
                                         @QueryParam("src") String src,
                                         @QueryParam("type") String type,
                                         @QueryParam("direction") @DefaultValue("out") String direction,
                                         @QueryParam("p") List<String> predicates,
                                         @QueryParam("limit") @DefaultValue("200") int limit) {
        if (src == null || src.isBlank()) {
            throw new WebApplicationException("Query param 'src' is required", Response.Status.BAD_REQUEST);
        }
        if (type == null || type.isBlank()) {
            throw new WebApplicationException("Query param 'type' is required", Response.Status.BAD_REQUEST);
        }

        // Get DataDomain from SecurityContext (set by SecurityFilter)
        DataDomain dataDomain = SecurityContext.getPrincipalContext()
                .map(PrincipalContext::getDataDomain)
                .orElse(null);

        if (dataDomain == null) {
            // Fallback: try to construct from X-Realm header for backward compatibility
            String tenant = headers.getHeaderString("X-Realm");
            if (tenant == null || tenant.isBlank()) {
                throw new WebApplicationException("No DataDomain available from security context and X-Realm header is missing",
                    Response.Status.BAD_REQUEST);
            }
            // Create minimal DataDomain for backward compatibility
            dataDomain = new DataDomain();
            dataDomain.setOrgRefName("ontology");
            dataDomain.setAccountNum("0000000000");
            dataDomain.setTenantId(tenant);
            dataDomain.setOwnerId("system");
            dataDomain.setDataSegment(0);
        }

        String dir = direction.toLowerCase(Locale.ROOT);
        boolean includeOut = dir.equals("out") || dir.equals("both");
        boolean includeIn  = dir.equals("in")  || dir.equals("both");
        Set<String> predicateFilter = predicates != null && !predicates.isEmpty()
                ? predicates.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet())
                : null;

        // Collect edges using full DataDomain scoping
        List<OntologyEdge> edges = new ArrayList<>();
        if (includeOut) {
            for (OntologyEdge e : edgeRepo.findBySrc(dataDomain, src)) {
                if (!type.equals(e.getSrcType())) continue; // ensure type matches the provided one for the focus node
                if (predicateFilter != null && !predicateFilter.contains(e.getP())) continue;
                edges.add(e);
            }
        }
        if (includeIn) {
            for (OntologyEdge e : edgeRepo.findByDst(dataDomain, src)) {
                if (!type.equals(e.getDstType())) continue; // when incoming, focus matches dst side
                if (predicateFilter != null && !predicateFilter.contains(e.getP())) continue;
                edges.add(e);
            }
        }
        if (edges.size() > limit) {
            edges = edges.subList(0, Math.max(0, limit));
        }

        // Build nodes and links
        Map<String, Map<String, Object>> elements = new LinkedHashMap<>();
        List<Map<String, Object>> links = new ArrayList<>();

        // Focus element
        String focusId = instanceId(type, src);
        elements.put(focusId, instanceRect(focusId, type, src, true));

        for (OntologyEdge e : edges) {
            boolean isOutgoing = src.equals(e.getSrc());
            String otherId;
            String label = e.getP();
            if (isOutgoing) {
                otherId = instanceId(e.getDstType(), e.getDst());
                elements.putIfAbsent(otherId, instanceRect(otherId, e.getDstType(), e.getDst(), false));
                links.add(link("Edge:" + e.getSrc() + ":" + e.getP() + ":" + e.getDst(), focusId, otherId, label, e.isInferred()));
            } else {
                otherId = instanceId(e.getSrcType(), e.getSrc());
                elements.putIfAbsent(otherId, instanceRect(otherId, e.getSrcType(), e.getSrc(), false));
                links.add(link("Edge:" + e.getSrc() + ":" + e.getP() + ":" + e.getDst(), otherId, focusId, label, e.isInferred()));
            }
        }

        // Simple grid layout
        int x = 60, y = 60, dx = 220, dy = 110, i = 0;
        for (var e : elements.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pos = (Map<String, Object>) e.get("position");
            int row = i / 5, col = i % 5; i++;
            pos.put("x", x + col * dx);
            pos.put("y", y + row * dy);
        }

        List<Map<String, Object>> cells = new ArrayList<>();
        cells.addAll(elements.values());
        cells.addAll(links);

        return Response.ok(Map.of("cells", cells)).build();
    }

    private static String instanceId(String type, String id) {
        return "Inst:" + type + ":" + id;
    }

    private static Map<String, Object> instanceRect(String id, String type, String value, boolean focus) {
        String label = type + "\n" + value;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "standard.Rectangle");
        m.put("id", id);
        m.put("size", Map.of("width", 180, "height", focus ? 60 : 50));
        m.put("position", new HashMap<>(Map.of("x", 0, "y", 0)));
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("label", Map.of("text", label));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("data-type", "instance");
        root.put("data-class", type);
        if (focus) root.put("stroke", "#1976d2");
        attrs.put("root", root);
        m.put("attrs", attrs);
        return m;
    }

    private static Map<String, Object> link(String id, String sourceId, String targetId, String label) {
        return Map.of(
                "type", "standard.Link",
                "id", id,
                "source", Map.of("id", sourceId),
                "target", Map.of("id", targetId),
                "labels", List.of(Map.of("attrs", Map.of("text", Map.of("text", label))))
        );
    }

    private static Map<String, Object> link(String id, String sourceId, String targetId, String label, boolean inferred) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        Map<String, Object> line = new LinkedHashMap<>();
        if (inferred) {
            line.put("strokeDasharray", "5,5");
            line.put("stroke", "#9e9e9e");
        } else {
            line.put("stroke", "#34495e");
        }
        attrs.put("line", line);
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("type", "standard.Link");
        link.put("id", id);
        link.put("source", Map.of("id", sourceId));
        link.put("target", Map.of("id", targetId));
        link.put("labels", List.of(Map.of("attrs", Map.of("text", Map.of("text", label + (inferred ? " (inferred)" : ""))))));
        link.put("attrs", attrs);
        link.put("data", Map.of("inferred", inferred));
        return link;
    }

    static class GraphBuilder {
        private final OntologyRegistry reg;
        GraphBuilder(OntologyRegistry reg) { this.reg = reg; }

        public List<Map<String, Object>> buildJointJSCELLS(String focus, int depth, Set<String> include) {
            Map<String, Map<String, Object>> elements = new LinkedHashMap<>();
            List<Map<String, Object>> links = new ArrayList<>();

            Map<String, PropertyDef> props = reg.properties();

            // Collect properties that are inferred/calculated:
            // 1. Properties implied by chains
            // 2. Properties explicitly marked as inferred in YAML
            Set<String> inferredProps = new HashSet<>();

            // Chain-implied properties
            for (var chain : reg.propertyChains()) {
                if (chain.implies() != null && !chain.implies().isBlank()) {
                    inferredProps.add(chain.implies());
                }
            }

            // Properties explicitly marked as inferred
            for (var p : props.values()) {
                if (p.inferred()) {
                    inferredProps.add(p.name());
                    System.out.println("GraphBuilder: Property '" + p.name() + "' marked as inferred");
                }
            }
            System.out.println("GraphBuilder: Total inferred properties: " + inferredProps);

            Set<String> classes = new LinkedHashSet<>();
            props.values().forEach(p -> { p.domain().ifPresent(classes::add); p.range().ifPresent(classes::add); });

            if (include.contains("classes")) {
                for (String c : classes) {
                    elements.putIfAbsent("Class:" + c, rect("Class:" + c, c, 160, 40, "class", false));
                }
            }
            if (include.contains("properties")) {
                for (var p : props.values()) {
                    boolean isInferred = inferredProps.contains(p.name());
                    elements.putIfAbsent("Prop:" + p.name(), rect("Prop:" + p.name(), p.name(), 180, 34, "property", isInferred));
                }
            }

            if (include.contains("properties")) {
                for (var p : props.values()) {
                    boolean isInferred = inferredProps.contains(p.name());
                    p.domain().ifPresent(d -> links.add(link("Edge:domain:" + p.name(), "Prop:" + p.name(), "Class:" + d, "domain", isInferred)));
                    p.range().ifPresent(r -> links.add(link("Edge:range:" + p.name(), "Prop:" + p.name(), "Class:" + r, "range", isInferred)));
                    for (String sp : p.subPropertyOf()) {
                        links.add(link("Edge:subPropertyOf:" + p.name() + "->" + sp, "Prop:" + p.name(), "Prop:" + sp, "subPropertyOf", false));
                    }
                    p.inverseOf().ifPresent(inv -> links.add(link("Edge:inverseOf:" + p.name() + "->" + inv, "Prop:" + p.name(), "Prop:" + inv, "inverseOf", false)));
                    if (p.symmetric()) {
                        links.add(link("Edge:symmetric:" + p.name(), "Prop:" + p.name(), "Prop:" + p.name(), "symmetric", false));
                    }
                }
            }

            int x = 60, y = 60, dx = 220, dy = 110, i = 0;
            for (var e : elements.values()) {
                int row = i / 5, col = i % 5; i++;
                @SuppressWarnings("unchecked")
                Map<String, Object> pos = (Map<String, Object>) e.get("position");
                pos.put("x", x + col * dx);
                pos.put("y", y + row * dy);
            }

            List<Map<String, Object>> cells = new ArrayList<>();
            cells.addAll(elements.values());
            cells.addAll(links);
            return cells;
        }

        private Map<String, Object> rect(String id, String label, int w, int h, String kind, boolean inferred) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "standard.Rectangle");
            m.put("id", id);
            m.put("size", Map.of("width", w, "height", h));
            m.put("position", new HashMap<>(Map.of("x", 0, "y", 0)));
            m.put("attrs", Map.of(
                    "label", Map.of("text", label),
                    "root", Map.of("data-type", kind)
            ));
            m.put("data", Map.of("inferred", inferred));
            return m;
        }

        private Map<String, Object> link(String id, String sourceId, String targetId, String label, boolean inferred) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "standard.Link");
            result.put("id", id);
            result.put("source", Map.of("id", sourceId));
            result.put("target", Map.of("id", targetId));
            result.put("labels", List.of(Map.of("attrs", Map.of("text", Map.of("text", label)))));
            result.put("data", Map.of("inferred", inferred));
            if (inferred) {
                // Add visual styling for inferred links
                result.put("attrs", Map.of("line", Map.of(
                    "strokeDasharray", "6,4",
                    "stroke", "#9575cd"
                )));
            }
            return result;
        }
    }
}

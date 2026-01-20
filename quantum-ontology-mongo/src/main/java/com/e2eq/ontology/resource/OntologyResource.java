package com.e2eq.ontology.resource;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.*;
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
public class OntologyResource {

    @Inject
    OntologyRegistry registry;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    TenantOntologyRegistryProvider registryProvider;

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
    public Response listProperties() {
        return Response.ok(registry.properties().values().stream()
                .sorted(Comparator.comparing(PropertyDef::name))
                .toList()).build();
    }

    @GET
    @Path("properties/{name}")
    @Operation(summary = "Get a property by name")
    @SecurityRequirement(name = "bearerAuth")
    public Response getProperty(@PathParam("name") String name) {
        return registry.propertyOf(name)
                .map(v -> Response.ok(v).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("propertyChains")
    @Operation(summary = "List property chains")
    @SecurityRequirement(name = "bearerAuth")
    public Response listPropertyChains() {
        return Response.ok(registry.propertyChains()).build();
    }

    @POST
    @Path("reindex")
    @RolesAllowed({"admin", "system"})
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Trigger full ontology reindex")
    @SecurityRequirement(name = "bearerAuth")
    public Response triggerReindex(@QueryParam("realm") @DefaultValue("default") String realm,
                                   @QueryParam("force") @DefaultValue("false") boolean force) {
        try {
            // Delegate to service
            com.e2eq.ontology.service.OntologyReindexer reindexer = io.quarkus.arc.Arc.container().instance(com.e2eq.ontology.service.OntologyReindexer.class).get();
            reindexer.runAsync(realm, force);
            Map<String, Object> body = Map.of(
                    "accepted", true,
                    "running", reindexer.isRunning(),
                    "status", reindexer.status()
            );
            return Response.accepted(body).build();
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

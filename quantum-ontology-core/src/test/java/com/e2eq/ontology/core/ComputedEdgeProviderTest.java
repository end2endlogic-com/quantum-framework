package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.OntologyClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ComputedEdgeProvider and related classes.
 */
public class ComputedEdgeProviderTest {

    private DataDomainInfo testDomain;

    @BeforeEach
    void setUp() {
        testDomain = new DataDomainInfo("testOrg", "0000000001", "testTenant", 0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ComputedEdgeProvenance tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void testProvenanceMinimal() {
        ComputedEdgeProvenance prov = ComputedEdgeProvenance.minimal("TestProvider");

        assertEquals("TestProvider", prov.providerId());
        assertNull(prov.sourceEntityType());
        assertNull(prov.sourceEntityId());
        assertTrue(prov.hierarchyPath().isEmpty());
        assertTrue(prov.resolvedLists().isEmpty());
        assertNotNull(prov.computedAt());
    }

    @Test
    void testProvenanceWithSourceEntity() {
        ComputedEdgeProvenance prov = ComputedEdgeProvenance.minimal("TestProvider")
            .withSourceEntity("Associate", "assoc-123");

        assertEquals("TestProvider", prov.providerId());
        assertEquals("Associate", prov.sourceEntityType());
        assertEquals("assoc-123", prov.sourceEntityId());
    }

    @Test
    void testProvenanceWithHierarchyPath() {
        List<ComputedEdgeProvenance.HierarchyContribution> path = List.of(
            new ComputedEdgeProvenance.HierarchyContribution("t1", "Territory", "west-region", true),
            new ComputedEdgeProvenance.HierarchyContribution("t2", "Territory", "west-sub", false)
        );

        ComputedEdgeProvenance prov = new ComputedEdgeProvenance(
            "TestProvider", "Associate", "assoc-123",
            path, List.of(), new Date()
        );

        assertEquals(2, prov.hierarchyPath().size());
        assertTrue(prov.hierarchyPath().get(0).isDirectAssignment());
        assertFalse(prov.hierarchyPath().get(1).isDirectAssignment());
    }

    @Test
    void testProvenanceWithListContribution() {
        List<ComputedEdgeProvenance.ListContribution> lists = List.of(
            new ComputedEdgeProvenance.ListContribution("list1", "LocationList", "STATIC", null, 5),
            new ComputedEdgeProvenance.ListContribution("list2", "LocationList", "DYNAMIC", "state:CA", 10)
        );

        ComputedEdgeProvenance prov = new ComputedEdgeProvenance(
            "TestProvider", "Associate", "assoc-123",
            List.of(), lists, new Date()
        );

        assertEquals(2, prov.resolvedLists().size());
        assertEquals("STATIC", prov.resolvedLists().get(0).mode());
        assertNull(prov.resolvedLists().get(0).filterString());
        assertEquals("DYNAMIC", prov.resolvedLists().get(1).mode());
        assertEquals("state:CA", prov.resolvedLists().get(1).filterString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ComputedEdgeProvider tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void testSimpleComputedEdgeProvider() {
        // Create a simple test provider
        TestComputedEdgeProvider provider = new TestComputedEdgeProvider();

        assertTrue(provider.supports(TestSourceEntity.class));
        assertFalse(provider.supports(String.class));
        assertEquals("TestComputedEdgeProvider", provider.getProviderId());
        assertEquals("canAccess", provider.getPredicate());
        assertEquals("Target", provider.getTargetTypeName());
    }

    @Test
    void testComputedEdgeProviderEdges() {
        TestComputedEdgeProvider provider = new TestComputedEdgeProvider();

        TestSourceEntity source = new TestSourceEntity("src-123");
        List<Reasoner.Edge> edges = provider.edges("realm1", testDomain, source);

        assertEquals(2, edges.size());

        // Verify first edge
        Reasoner.Edge edge1 = edges.get(0);
        assertEquals("src-123", edge1.srcId());
        assertEquals("TestSourceEntity", edge1.srcType());
        assertEquals("canAccess", edge1.p());
        assertEquals("Target", edge1.dstType());
        assertFalse(edge1.inferred()); // computed edges are not "inferred" by reasoner
        assertTrue(edge1.prov().isPresent());
        assertEquals("computed", edge1.prov().get().rule());
    }

    @Test
    void testResolveOntologyClassId_withAnnotation() {
        // Entity with @OntologyClass(id = "Credential") should return "Credential"
        String id = ComputedEdgeProvider.resolveOntologyClassId(AnnotatedSourceEntity.class);
        assertEquals("Credential", id);
    }

    @Test
    void testResolveOntologyClassId_withEmptyId() {
        // Entity with @OntologyClass but empty id should return simple class name
        String id = ComputedEdgeProvider.resolveOntologyClassId(AnnotatedSourceEntityEmptyId.class);
        assertEquals("AnnotatedSourceEntityEmptyId", id);
    }

    @Test
    void testResolveOntologyClassId_withoutAnnotation() {
        // Entity without @OntologyClass should return simple class name
        String id = ComputedEdgeProvider.resolveOntologyClassId(TestSourceEntity.class);
        assertEquals("TestSourceEntity", id);
    }

    @Test
    void testGetSourceTypeName_usesOntologyClassId() {
        AnnotatedSourceProvider provider = new AnnotatedSourceProvider();

        // getSourceTypeName should return the ontology class ID, not the simple class name
        assertEquals("Credential", provider.getSourceTypeName());
    }

    @Test
    void testEdgesUseOntologyClassId() {
        AnnotatedSourceProvider provider = new AnnotatedSourceProvider();

        AnnotatedSourceEntity source = new AnnotatedSourceEntity("cred-123");
        List<Reasoner.Edge> edges = provider.edges("realm1", testDomain, source);

        assertEquals(1, edges.size());

        // The edge source type should use the ontology class ID "Credential",
        // not the Java class name "AnnotatedSourceEntity"
        Reasoner.Edge edge = edges.get(0);
        assertEquals("cred-123", edge.srcId());
        assertEquals("Credential", edge.srcType()); // This is the key assertion
        assertEquals("canAuthenticate", edge.p());
        assertEquals("System", edge.dstType());
    }

    @Test
    void testGetSourceTypeName_canBeOverridden() {
        OverriddenSourceTypeProvider provider = new OverriddenSourceTypeProvider();

        // Override should take precedence
        assertEquals("CustomSourceType", provider.getSourceTypeName());

        TestSourceEntity source = new TestSourceEntity("src-123");
        List<Reasoner.Edge> edges = provider.edges("realm1", testDomain, source);

        assertEquals(1, edges.size());
        assertEquals("CustomSourceType", edges.get(0).srcType());
    }

    @Test
    void testComputedEdgeProviderWithProvenance() {
        TestComputedEdgeProviderWithProvenance provider = new TestComputedEdgeProviderWithProvenance();

        TestSourceEntity source = new TestSourceEntity("src-456");
        List<Reasoner.Edge> edges = provider.edges("realm1", testDomain, source);

        assertEquals(1, edges.size());

        Reasoner.Edge edge = edges.get(0);
        assertTrue(edge.prov().isPresent());

        @SuppressWarnings("unchecked")
        Map<String, Object> provMap = edge.prov().get().inputs();
        assertEquals("TestComputedEdgeProviderWithProvenance", provMap.get("providerId"));
        assertNotNull(provMap.get("computedAt"));
        assertNotNull(provMap.get("hierarchyPath"));
    }

    @Test
    void testComputationContext() {
        ComputedEdgeProvider.ComputationContext ctx =
            new ComputedEdgeProvider.ComputationContext("realm1", testDomain, "TestProvider");

        assertEquals("realm1", ctx.getRealmId());
        assertEquals(testDomain, ctx.getDataDomainInfo());
        assertEquals("TestProvider", ctx.getProviderId());

        // Add contributions
        ctx.addHierarchyContribution("node1", "Territory", "west", true);
        ctx.addHierarchyContribution("node2", "Territory", "west-sub", false);
        ctx.addListContribution("list1", "LocationList", "DYNAMIC", "state:CA", 5);

        assertEquals(2, ctx.getHierarchyPath().size());
        assertEquals(1, ctx.getResolvedLists().size());

        // Build provenance
        ComputedEdgeProvenance prov = ctx.buildProvenance("Associate", "assoc-123");
        assertEquals("TestProvider", prov.providerId());
        assertEquals("Associate", prov.sourceEntityType());
        assertEquals(2, prov.hierarchyPath().size());
        assertEquals(1, prov.resolvedLists().size());

        // Clear and verify
        ctx.clearProvenance();
        assertTrue(ctx.getHierarchyPath().isEmpty());
        assertTrue(ctx.getResolvedLists().isEmpty());
    }

    @Test
    void testComputedTargetWithProvenance() {
        ComputedEdgeProvenance prov = ComputedEdgeProvenance.minimal("TestProvider")
            .withSourceEntity("Source", "src-1");

        ComputedEdgeProvider.ComputedTarget target = new ComputedEdgeProvider.ComputedTarget("target-123", prov);

        assertEquals("target-123", target.targetId());
        assertNotNull(target.provenance());
        assertEquals("TestProvider", target.provenance().providerId());
    }

    @Test
    void testComputedTargetWithoutProvenance() {
        ComputedEdgeProvider.ComputedTarget target = new ComputedEdgeProvider.ComputedTarget("target-456");

        assertEquals("target-456", target.targetId());
        assertNull(target.provenance());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ComputedEdgeRegistry tests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void testRegistryRegisterAndLookup() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        TestComputedEdgeProvider provider = new TestComputedEdgeProvider();

        registry.register(provider);

        assertEquals(1, registry.getAllProviders().size());
        assertTrue(registry.getProvider("TestComputedEdgeProvider").isPresent());
        assertEquals(provider, registry.getProvider("TestComputedEdgeProvider").get());
    }

    @Test
    void testRegistryDuplicateRegistration() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        TestComputedEdgeProvider provider = new TestComputedEdgeProvider();

        registry.register(provider);
        registry.register(provider); // duplicate

        assertEquals(1, registry.getAllProviders().size());
    }

    @Test
    void testRegistryUnregister() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        TestComputedEdgeProvider provider = new TestComputedEdgeProvider();

        registry.register(provider);
        assertEquals(1, registry.getAllProviders().size());

        registry.unregister(provider);
        assertEquals(0, registry.getAllProviders().size());
        assertTrue(registry.getProvider("TestComputedEdgeProvider").isEmpty());
    }

    @Test
    void testRegistryDependencyTracking() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        TestProviderWithDependencies provider = new TestProviderWithDependencies();

        registry.register(provider);

        // Check dependency types
        assertTrue(registry.hasDependentsFor(DependencyEntity.class));
        assertFalse(registry.hasDependentsFor(String.class));

        // Get providers for dependency
        List<ComputedEdgeProvider<?>> providers = registry.getProvidersForDependency(DependencyEntity.class);
        assertEquals(1, providers.size());
        assertEquals(provider, providers.get(0));
    }

    @Test
    void testRegistryFindAffectedSources() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        TestProviderWithDependencies provider = new TestProviderWithDependencies();

        registry.register(provider);

        Map<ComputedEdgeProvider<?>, Set<String>> affected =
            registry.findAffectedSources("realm1", testDomain, DependencyEntity.class, "dep-123");

        assertEquals(1, affected.size());
        assertTrue(affected.containsKey(provider));
        assertEquals(Set.of("src-1", "src-2"), affected.get(provider));
    }

    @Test
    void testRegistryStats() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        registry.register(new TestComputedEdgeProvider());
        registry.register(new TestProviderWithDependencies());

        Map<String, Object> stats = registry.getStats();

        assertEquals(2, stats.get("totalProviders"));
    }

    @Test
    void testRegistryClear() {
        ComputedEdgeRegistry registry = new ComputedEdgeRegistry();
        registry.register(new TestComputedEdgeProvider());
        registry.register(new TestProviderWithDependencies());

        assertEquals(2, registry.getAllProviders().size());

        registry.clear();

        assertEquals(0, registry.getAllProviders().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test fixtures
    // ─────────────────────────────────────────────────────────────────────────

    /** Simple test source entity */
    static class TestSourceEntity {
        private final String id;

        TestSourceEntity(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /** Source entity with @OntologyClass annotation specifying a custom ID */
    @OntologyClass(id = "Credential")
    static class AnnotatedSourceEntity {
        private final String id;

        AnnotatedSourceEntity(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /** Source entity with @OntologyClass annotation but empty id */
    @OntologyClass(id = "")
    static class AnnotatedSourceEntityEmptyId {
        private final String id;

        AnnotatedSourceEntityEmptyId(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /** Simple dependency entity for testing */
    static class DependencyEntity {
        private final String id;

        DependencyEntity(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /** Simple computed edge provider for testing */
    static class TestComputedEdgeProvider extends ComputedEdgeProvider<TestSourceEntity> {
        @Override
        public Class<TestSourceEntity> getSourceType() {
            return TestSourceEntity.class;
        }

        @Override
        public String getPredicate() {
            return "canAccess";
        }

        @Override
        public String getTargetTypeName() {
            return "Target";
        }

        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, TestSourceEntity source) {
            return Set.of(
                new ComputedTarget("target-1"),
                new ComputedTarget("target-2")
            );
        }
    }

    /** Provider that includes provenance in computed targets */
    static class TestComputedEdgeProviderWithProvenance extends ComputedEdgeProvider<TestSourceEntity> {
        @Override
        public Class<TestSourceEntity> getSourceType() {
            return TestSourceEntity.class;
        }

        @Override
        public String getPredicate() {
            return "canView";
        }

        @Override
        public String getTargetTypeName() {
            return "Resource";
        }

        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, TestSourceEntity source) {
            context.addHierarchyContribution("h1", "Hierarchy", "node1", true);
            context.addListContribution("l1", "List", "STATIC", null, 3);

            ComputedEdgeProvenance prov = context.buildProvenance(
                getSourceType().getSimpleName(),
                source.getId()
            );

            return Set.of(new ComputedTarget("target-with-prov", prov));
        }
    }

    /** Provider with dependencies for testing incremental updates */
    static class TestProviderWithDependencies extends ComputedEdgeProvider<TestSourceEntity> {
        @Override
        public Class<TestSourceEntity> getSourceType() {
            return TestSourceEntity.class;
        }

        @Override
        public String getPredicate() {
            return "dependsOn";
        }

        @Override
        public String getTargetTypeName() {
            return "Dependency";
        }

        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, TestSourceEntity source) {
            return Set.of(new ComputedTarget("dep-target"));
        }

        @Override
        public Set<Class<?>> getDependencyTypes() {
            return Set.of(DependencyEntity.class);
        }

        @Override
        public Set<String> getAffectedSourceIds(ComputationContext context, Class<?> dependencyType, String dependencyId) {
            if (DependencyEntity.class.isAssignableFrom(dependencyType)) {
                // Return some affected source IDs for testing
                return Set.of("src-1", "src-2");
            }
            return Set.of();
        }
    }

    /** Provider for annotated source entity - tests ontology class ID resolution */
    static class AnnotatedSourceProvider extends ComputedEdgeProvider<AnnotatedSourceEntity> {
        @Override
        public Class<AnnotatedSourceEntity> getSourceType() {
            return AnnotatedSourceEntity.class;
        }

        @Override
        public String getPredicate() {
            return "canAuthenticate";
        }

        @Override
        public String getTargetTypeName() {
            return "System";
        }

        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, AnnotatedSourceEntity source) {
            return Set.of(new ComputedTarget("system-1"));
        }
    }

    /** Provider that overrides getSourceTypeName() */
    static class OverriddenSourceTypeProvider extends ComputedEdgeProvider<TestSourceEntity> {
        @Override
        public Class<TestSourceEntity> getSourceType() {
            return TestSourceEntity.class;
        }

        @Override
        public String getSourceTypeName() {
            return "CustomSourceType"; // Override the default behavior
        }

        @Override
        public String getPredicate() {
            return "customPredicate";
        }

        @Override
        public String getTargetTypeName() {
            return "CustomTarget";
        }

        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, TestSourceEntity source) {
            return Set.of(new ComputedTarget("custom-target"));
        }
    }
}

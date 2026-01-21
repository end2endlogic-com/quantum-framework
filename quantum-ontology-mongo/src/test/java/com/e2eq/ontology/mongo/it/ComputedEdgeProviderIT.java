package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.*;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.mongo.OntologyWriteHook;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ComputedEdgeProvider functionality.
 *
 * <p>Tests the end-to-end flow of:</p>
 * <ul>
 *   <li>ComputedEdgeProvider generating edges with provenance</li>
 *   <li>ComputedEdgeRegistry tracking providers and dependencies</li>
 *   <li>Integration with OntologyWriteHook</li>
 * </ul>
 */
@QuarkusTest
public class ComputedEdgeProviderIT {

    private static final String TENANT = "computed-edge-test";

    @Inject MorphiaDatastore datastore;
    @Inject OntologyEdgeRepo edgeRepo;
    @Inject OntologyWriteHook writeHook;
    @Inject ComputedEdgeRegistry registry;

    private DataDomain testDataDomain;

    @BeforeEach
    void setup() {
        edgeRepo.deleteAll();
        datastore.getDatabase().getCollection("it_prov_sources").deleteMany(new Document());

        // Create test DataDomain
        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("computed-test-org");
        testDataDomain.setAccountNum("9999999999");
        testDataDomain.setTenantId(TENANT);
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);

        // Register our test provider
        registry.clear();
        registry.register(new TestHierarchyComputedProvider());
    }

    @Test
    void testComputedEdgeProviderCreatesEdgesWithProvenance() {
        // Given: a source entity that our provider handles
        ItProvSource source = new ItProvSource();
        source.setRefName("COMP-SRC-1");
        source.setDataDomain(testDataDomain);
        source.setProviderTargetRef("hierarchy-node-1"); // Used by our test provider
        datastore.save(source);

        // When: trigger the write hook (which invokes OntologyEdgeProviders)
        writeHook.afterPersist(TENANT, source);

        // Then: edges should be created by our computed provider
        List<OntologyEdge> edges = edgeRepo.findBySrc(testDataDomain, "COMP-SRC-1");

        // Find edges with predicate "computedCanAccess" (from our test provider)
        List<OntologyEdge> computedEdges = edges.stream()
            .filter(e -> "computedCanAccess".equals(e.getP()))
            .toList();

        assertEquals(2, computedEdges.size(), "Should have 2 computed edges");

        // Verify provenance on computed edges
        for (OntologyEdge edge : computedEdges) {
            Map<String, Object> prov = edge.getProv();
            assertNotNull(prov, "Provenance should be present");
            assertEquals("computed", prov.get("rule"), "Rule should be 'computed'");
            assertEquals("TestHierarchyComputedProvider", prov.get("providerId"));
            assertNotNull(prov.get("computedAt"));

            // Verify hierarchy path in provenance
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hierarchyPath = (List<Map<String, Object>>) prov.get("hierarchyPath");
            assertNotNull(hierarchyPath, "Hierarchy path should be present");
            assertFalse(hierarchyPath.isEmpty(), "Hierarchy path should not be empty");
        }
    }

    @Test
    void testComputedEdgeRegistryTracksProviders() {
        // Registry was set up in @BeforeEach
        assertEquals(1, registry.getAllProviders().size());

        Optional<ComputedEdgeProvider<?>> provider = registry.getProvider("TestHierarchyComputedProvider");
        assertTrue(provider.isPresent());
        assertEquals(ItProvSource.class, provider.get().getSourceType());
    }

    @Test
    void testComputedEdgeRegistryDependencyTracking() {
        // Given: our test provider declares ItProvTarget as a dependency
        assertTrue(registry.hasDependentsFor(ItProvTarget.class));

        List<ComputedEdgeProvider<?>> providers = registry.getProvidersForDependency(ItProvTarget.class);
        assertEquals(1, providers.size());
        assertEquals("TestHierarchyComputedProvider", providers.get(0).getProviderId());
    }

    @Test
    void testComputedEdgeRegistryFindAffectedSources() {
        DataDomainInfo domainInfo = new DataDomainInfo(
            testDataDomain.getOrgRefName(),
            testDataDomain.getAccountNum(),
            testDataDomain.getTenantId(),
            testDataDomain.getDataSegment()
        );

        // When: we ask for affected sources when ItProvTarget changes
        Map<ComputedEdgeProvider<?>, Set<String>> affected =
            registry.findAffectedSources(TENANT, domainInfo, ItProvTarget.class, "target-123");

        // Then: our provider should report affected sources
        assertEquals(1, affected.size());
        assertTrue(affected.values().iterator().next().contains("affected-source-1"));
    }

    @Test
    void testComputationContextProvenanceTracking() {
        DataDomainInfo domainInfo = new DataDomainInfo(
            testDataDomain.getOrgRefName(),
            testDataDomain.getAccountNum(),
            testDataDomain.getTenantId(),
            testDataDomain.getDataSegment()
        );

        ComputedEdgeProvider.ComputationContext ctx =
            new ComputedEdgeProvider.ComputationContext(TENANT, domainInfo, "TestProvider");

        // Add hierarchy contribution
        ctx.addHierarchyContribution("node-1", "Territory", "west-region", true);
        ctx.addHierarchyContribution("node-2", "Territory", "west-sub", false);

        // Add list contribution
        ctx.addListContribution("list-1", "LocationList", "DYNAMIC", "state:CA", 10);

        // Build provenance
        ComputedEdgeProvenance prov = ctx.buildProvenance("Associate", "assoc-123");

        assertEquals("TestProvider", prov.providerId());
        assertEquals("Associate", prov.sourceEntityType());
        assertEquals("assoc-123", prov.sourceEntityId());
        assertEquals(2, prov.hierarchyPath().size());
        assertEquals(1, prov.resolvedLists().size());

        // Verify hierarchy details
        assertEquals("node-1", prov.hierarchyPath().get(0).nodeId());
        assertTrue(prov.hierarchyPath().get(0).isDirectAssignment());
        assertEquals("node-2", prov.hierarchyPath().get(1).nodeId());
        assertFalse(prov.hierarchyPath().get(1).isDirectAssignment());

        // Verify list details
        assertEquals("DYNAMIC", prov.resolvedLists().get(0).mode());
        assertEquals("state:CA", prov.resolvedLists().get(0).filterString());
        assertEquals(10, prov.resolvedLists().get(0).itemCount());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test Provider Implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test computed edge provider that simulates hierarchy-based edge computation.
     *
     * <p>This provider creates "computedCanAccess" edges from ItProvSource to
     * simulated target entities, with rich provenance information.</p>
     */
    @ApplicationScoped
    public static class TestHierarchyComputedProvider extends ComputedEdgeProvider<ItProvSource> {

        @Override
        protected Class<ItProvSource> getSourceType() {
            return ItProvSource.class;
        }

        @Override
        protected String getPredicate() {
            return "computedCanAccess";
        }

        @Override
        protected String getTargetTypeName() {
            return "ComputedTarget";
        }

        @Override
        protected String extractId(ItProvSource entity) {
            return entity.getRefName();
        }

        @Override
        protected Set<ComputedTarget> computeTargets(ComputationContext context, ItProvSource source) {
            Set<ComputedTarget> targets = new HashSet<>();

            // Simulate hierarchy traversal
            String hierarchyRef = source.getProviderTargetRef();
            if (hierarchyRef != null && !hierarchyRef.isBlank()) {
                // Record hierarchy contribution
                context.addHierarchyContribution(hierarchyRef, "TestHierarchy", hierarchyRef + "-name", true);

                // Simulate child hierarchy node
                context.addHierarchyContribution(hierarchyRef + "-child", "TestHierarchy", hierarchyRef + "-child-name", false);

                // Record list resolution
                context.addListContribution("list-" + hierarchyRef, "TestList", "STATIC", null, 2);

                // Create provenance for this path
                ComputedEdgeProvenance prov1 = context.buildProvenance(
                    getSourceType().getSimpleName(),
                    source.getRefName()
                );
                targets.add(new ComputedTarget("target-from-" + hierarchyRef, prov1));

                context.clearProvenance();

                // Add another target with different path
                context.addHierarchyContribution(hierarchyRef, "TestHierarchy", hierarchyRef + "-name", true);
                context.addListContribution("list-" + hierarchyRef + "-2", "TestList", "DYNAMIC", "filter:test", 5);

                ComputedEdgeProvenance prov2 = context.buildProvenance(
                    getSourceType().getSimpleName(),
                    source.getRefName()
                );
                targets.add(new ComputedTarget("target-from-" + hierarchyRef + "-alt", prov2));
            }

            return targets;
        }

        @Override
        public Set<Class<?>> getDependencyTypes() {
            return Set.of(ItProvTarget.class);
        }

        @Override
        public Set<String> getAffectedSourceIds(ComputationContext context, Class<?> dependencyType, String dependencyId) {
            if (ItProvTarget.class.isAssignableFrom(dependencyType)) {
                // In a real implementation, we would query the database
                // to find sources that reference this target
                return Set.of("affected-source-1", "affected-source-2");
            }
            return Set.of();
        }
    }
}

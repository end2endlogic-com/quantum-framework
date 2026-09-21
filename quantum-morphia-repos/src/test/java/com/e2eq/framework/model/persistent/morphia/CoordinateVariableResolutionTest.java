package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.security.DataDomainResolution;
import com.e2eq.framework.model.security.DataDomainResolver;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for coordinate and principal variable resolution, namespacing, and query macros.
 */
public class CoordinateVariableResolutionTest {

    public static class TestModel extends UnversionedBaseModel {
        protected String jurisdiction;
        protected String costCenter;
        protected String tier;
        protected Double confidence;
        protected java.util.Date validFrom;
        protected java.util.Date validTo;

        @Override
        public String bmFunctionalArea() { return "test-area"; }
        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    private PrincipalContext createPrincipal() {
        Map<String, Object> custom = new HashMap<>();
        custom.put("costCenter", "CC-409");
        custom.put("clearanceLevel", 3);
        custom.put("allowedRoles", List.of("ANALYST", "MANAGER"));

        return new PrincipalContext.Builder()
                .withUserId("usr-123")
                .withDefaultRealm("finance-realm")
                .withDataDomain(DataDomain.builder()
                        .orgRefName("e2e")
                        .accountNum("ACC001")
                        .tenantId("tenant-alpha")
                        .dataSegment(2)
                        .ownerId("usr-123")
                        .build())
                .withCustomProperties(custom)
                .build();
    }

    private ResourceContext createResource() {
        return new ResourceContext.Builder()
                .withArea("test-area")
                .withFunctionalDomain("test-domain")
                .withAction("read")
                .withResourceId("res-001")
                .build();
    }

    private DataDomainResolver mockResolver(Map<String, Object> facetFilters) {
        return new DataDomainResolver() {
            @Override
            public DataDomain resolveForCreate(String functionalArea, String functionalDomain) {
                return null;
            }

            @Override
            public DataDomainResolution resolveForQuery(PrincipalContext pcontext, String area, String functionalDomain, Class<?> modelClass) {
                DataDomain dd = pcontext.getDataDomain();
                return DataDomainResolution.resolved(dd, null, facetFilters);
            }
        };
    }

    @Nested
    @DisplayName("Namespace resolution in standard variable map")
    class StandardVariableMapTests {

        @Test
        @DisplayName("Resolves facet, principal, and dd namespaces correctly")
        void testNamespacedVariables() {
            PrincipalContext pc = createPrincipal();
            ResourceContext rc = createResource();
            Map<String, Object> facetFilters = Map.of(
                    "jurisdiction", "EU",
                    "allowedTiers", List.of("GOLD", "PLATINUM")
            );
            DataDomainResolver resolver = mockResolver(facetFilters);

            Map<String, String> vars = MorphiaUtils.createStandardVariableMapFrom(pc, rc, TestModel.class, resolver);

            // Facet / coordinate namespaces
            assertEquals("EU", vars.get("jurisdiction"));
            assertEquals("EU", vars.get("facet.jurisdiction"));
            assertEquals("EU", vars.get("coord.jurisdiction"));

            // Principal namespaces
            assertEquals("CC-409", vars.get("costCenter"));
            assertEquals("CC-409", vars.get("principal.costCenter"));
            assertEquals("3", vars.get("principal.clearanceLevel"));
            assertEquals("usr-123", vars.get("principal.userId"));
            assertEquals("finance-realm", vars.get("principal.defaultRealm"));

            // DataDomain tuple aliases
            assertEquals("tenant-alpha", vars.get("dd.tenantId"));
            assertEquals("ACC001", vars.get("dd.accountNum"));
            assertEquals("2", vars.get("dd.dataSegment"));
            assertEquals("e2e", vars.get("dd.orgRefName"));
            assertEquals("usr-123", vars.get("dd.ownerId"));
        }
    }

    @Nested
    @DisplayName("Object bundle and IN expansion")
    class VariableBundleTests {

        @Test
        @DisplayName("Preserves collection objects in bundle under explicit namespaces")
        void testTypedObjectBundle() {
            PrincipalContext pc = createPrincipal();
            ResourceContext rc = createResource();
            List<String> tiers = List.of("GOLD", "PLATINUM");
            Map<String, Object> facetFilters = Map.of(
                    "jurisdiction", "EU",
                    "allowedTiers", tiers
            );
            DataDomainResolver resolver = mockResolver(facetFilters);

            MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pc, rc, null, TestModel.class, resolver);

            assertNotNull(bundle.objects.get("facet.allowedTiers"));
            assertEquals(tiers, bundle.objects.get("facet.allowedTiers"));
            assertEquals(tiers, bundle.objects.get("coord.allowedTiers"));
            assertEquals(tiers, bundle.objects.get("allowedTiers"));

            List<String> roles = List.of("ANALYST", "MANAGER");
            assertEquals(roles, bundle.objects.get("principal.allowedRoles"));
            assertEquals(roles, bundle.objects.get("allowedRoles"));

            // Verify filter compilation using :^ with single collection variable
            Filter filter = MorphiaUtils.convertToFilter("tier:^${facet.allowedTiers}", bundle, TestModel.class);
            assertNotNull(filter, "Filter should compile successfully using ${facet.allowedTiers}");
        }
    }

    @Nested
    @DisplayName("Query macro normalization")
    class MacroNormalizationTests {

        @Test
        @DisplayName("Expands @asOf and @minConfidence macros")
        void testMacroExpansion() {
            String query = "@asOf(2026-09-20T00:00:00Z) && @minConfidence(0.85)";
            String normalized = MorphiaUtils.normalizeOperators(query);

            assertTrue(normalized.contains("validFrom:<=2026-09-20T00:00:00Z"));
            assertTrue(normalized.contains("validTo:>=2026-09-20T00:00:00Z"));
            assertTrue(normalized.contains("confidence:>=0.85"));

            // Verify with quotes around timestamp
            String quotedQuery = "@asOf(\"2026-09-20T00:00:00Z\")";
            String quotedNormalized = MorphiaUtils.normalizeOperators(quotedQuery);
            assertTrue(quotedNormalized.contains("validFrom:<=2026-09-20T00:00:00Z"));
            assertFalse(quotedNormalized.contains("\"2026-09-20T00:00:00Z\""));

            // Verify edges. prefix handling
            String edgesQuery = "edges.@asOf(2026-09-20T00:00:00Z) && edges.@minConfidence(0.9)";
            String edgesNormalized = MorphiaUtils.normalizeOperators(edgesQuery);
            assertTrue(edgesNormalized.contains("edges.validFrom:<=2026-09-20T00:00:00Z"));
            assertTrue(edgesNormalized.contains("edges.confidence:>=0.9"));
        }

        @Test
        @DisplayName("Validates and compiles queries containing macros")
        void testMacroFilterCompilation() {
            PrincipalContext pc = createPrincipal();
            ResourceContext rc = createResource();
            MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pc, rc, null, TestModel.class, null);

            String query = "@asOf(2026-09-20T00:00:00Z) && @minConfidence(0.75)";
            assertTrue(MorphiaUtils.validateQueryString(query).isPresent(), "Query with macros should validate");

            Filter filter = MorphiaUtils.convertToFilter(query, bundle, TestModel.class);
            assertNotNull(filter, "Filter should compile successfully from query with macros");
        }
    }
}

package com.e2eq.framework.model.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataDomainComponentBindingAndPolicyEntryTest {

    @Test
    void testFacetBindingInComponentBinding() {
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("org-1"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_attr"));

        // Test open facet bindings
        binding.setFacetBinding("jurisdiction", DataDomainComponentBinding.Binding.literal("EU"));
        binding.setFacetBinding("department", DataDomainComponentBinding.Binding.fromAttribute("dept_code"));

        assertNotNull(binding.getFacetBinding("jurisdiction"));
        assertEquals(DataDomainComponentBinding.Kind.LITERAL, binding.getFacetBinding("jurisdiction").getKind());
        assertEquals("EU", binding.getFacetBinding("jurisdiction").getLiteralValue());

        assertNotNull(binding.getFacetBinding("department"));
        assertEquals(DataDomainComponentBinding.Kind.FROM_ATTRIBUTE, binding.getFacetBinding("department").getKind());
        assertEquals("dept_code", binding.getFacetBinding("department").getAttributeName());

        assertNull(binding.getFacetBinding("nonExistent"));
    }

    @Test
    void testDataDomainPolicyEntryFilterAndFacetFilters() {
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();

        assertFalse(entry.hasFilter());
        assertFalse(entry.hasFacetFilters());

        // Set string-based facet scope filter
        entry.setFilter("jurisdiction == 'EU' && dataSegment <= 2");
        assertTrue(entry.hasFilter());
        assertEquals("jurisdiction == 'EU' && dataSegment <= 2", entry.getFilter());

        // Set structured facet filters
        entry.setFacetFilter("jurisdiction", "EU");
        entry.setFacetFilter("maxSegment", 2);
        assertTrue(entry.hasFacetFilters());
        assertEquals("EU", entry.getFacetFilter("jurisdiction"));
        assertEquals(2, entry.getFacetFilter("maxSegment"));
        assertNull(entry.getFacetFilter("unknown"));
    }
}

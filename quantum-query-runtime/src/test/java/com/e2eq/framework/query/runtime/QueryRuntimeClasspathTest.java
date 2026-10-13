package com.e2eq.framework.query.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class QueryRuntimeClasspathTest {
    @Test
    @SuppressWarnings("unchecked")
    void writeGuardEntryPointWorksWithoutRepositoryOrRestAdapters() throws Exception {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.e2eq.framework.model.persistent.morphia.MorphiaRepo"));
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.e2eq.framework.api.query.QueryGatewayResource"));
        // Preserve the reflective entry point consumed by the write-permission guard.
        Class<?> runtime = Class.forName("com.e2eq.framework.query.runtime.QueryPredicates");
        Predicate<JsonNode> predicate = (Predicate<JsonNode>) runtime
                .getMethod("compilePredicate", String.class, Map.class, Map.class)
                .invoke(null, "dataDomain.tenantId:${pTenantId}", Map.of("pTenantId", "tenant-a"), Map.of());
        assertTrue(predicate.test(QueryPredicates.toJsonNode(
                Map.of("dataDomain", Map.of("tenantId", "tenant-a")))));
        assertFalse(predicate.test(QueryPredicates.toJsonNode(
                Map.of("dataDomain", Map.of("tenantId", "tenant-b")))));
    }
}

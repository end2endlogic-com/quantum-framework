package com.e2eq.framework.metering;

import com.e2eq.framework.model.persistent.usage.UsageScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UsageScope}.
 */
@DisplayName("UsageScope unit tests")
class UsageScopeTest {

    @Test
    @DisplayName("matchesApi returns false when apiIdentifiers empty")
    void matchesApi_emptyIdentifiers() {
        UsageScope scope = new UsageScope(List.of(), List.of(), List.of());
        assertFalse(scope.matchesApi("integration", "query", "find"));
    }

    @Test
    @DisplayName("matchesApi returns true when identifier matches area/domain/action")
    void matchesApi_exactMatch() {
        UsageScope scope = new UsageScope(
            List.of("integration/query/find", "integration/query/save"),
            List.of(),
            List.of()
        );
        assertTrue(scope.matchesApi("integration", "query", "find"));
        assertTrue(scope.matchesApi("integration", "query", "save"));
        assertFalse(scope.matchesApi("integration", "query", "delete"));
    }

    @Test
    @DisplayName("matchesApi returns true for wildcard *")
    void matchesApi_wildcard() {
        UsageScope scope = new UsageScope(List.of("*"), List.of(), List.of());
        assertTrue(scope.matchesApi("integration", "query", "find"));
        assertTrue(scope.matchesApi("any", "domain", "action"));
    }

    @Test
    @DisplayName("matchesTool returns false when toolNames empty")
    void matchesTool_emptyToolNames() {
        UsageScope scope = new UsageScope(List.of(), List.of(), List.of());
        assertFalse(scope.matchesTool("query_find"));
    }

    @Test
    @DisplayName("matchesTool returns true when tool name in list")
    void matchesTool_exactMatch() {
        UsageScope scope = new UsageScope(
            List.of(),
            List.of("query_find", "query_save"),
            List.of()
        );
        assertTrue(scope.matchesTool("query_find"));
        assertTrue(scope.matchesTool("query_save"));
        assertFalse(scope.matchesTool("query_delete"));
    }

    @Test
    @DisplayName("matchesTool returns true for wildcard *")
    void matchesTool_wildcard() {
        UsageScope scope = new UsageScope(List.of(), List.of("*"), List.of());
        assertTrue(scope.matchesTool("query_find"));
    }

    @Test
    @DisplayName("matchesLlmConfig returns true when llmConfigKeys empty and key null")
    void matchesLlmConfig_emptyKeys_nullKey() {
        UsageScope scope = new UsageScope(List.of(), List.of(), List.of());
        assertTrue(scope.matchesLlmConfig(null));
    }

    @Test
    @DisplayName("matchesLlmConfig returns true when key in llmConfigKeys")
    void matchesLlmConfig_exactMatch() {
        UsageScope scope = new UsageScope(List.of(), List.of(), List.of("standard", "premium"));
        assertTrue(scope.matchesLlmConfig("standard"));
        assertTrue(scope.matchesLlmConfig("premium"));
        assertFalse(scope.matchesLlmConfig("other"));
    }

    @Test
    @DisplayName("matchesLlmConfig wildcard matches any key")
    void matchesLlmConfig_wildcard() {
        UsageScope scope = new UsageScope(List.of(), List.of(), List.of("*"));
        assertTrue(scope.matchesLlmConfig("any-key"));
    }
}

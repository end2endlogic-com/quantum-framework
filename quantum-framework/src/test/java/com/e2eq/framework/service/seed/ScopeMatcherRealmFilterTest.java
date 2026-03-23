package com.e2eq.framework.service.seed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for realm-level include/exclude filtering in ScopeMatcher.
 */
class ScopeMatcherRealmFilterTest {

    @Test
    void includeRealms_allowsListedRealm() {
        SeedScope scope = new SeedScope();
        scope.setIncludeRealms(List.of("acme-com", "beta-com"));
        assertTrue(ScopeMatcher.isRealmAllowed(scope, "acme-com"));
        assertTrue(ScopeMatcher.isRealmAllowed(scope, "beta-com"));
    }

    @Test
    void includeRealms_blocksUnlistedRealm() {
        SeedScope scope = new SeedScope();
        scope.setIncludeRealms(List.of("acme-com", "beta-com"));
        assertFalse(ScopeMatcher.isRealmAllowed(scope, "other-com"));
    }

    @Test
    void excludeRealms_blocksListedRealm() {
        SeedScope scope = new SeedScope();
        scope.setExcludeRealms(List.of("test-com", "staging-com"));
        assertFalse(ScopeMatcher.isRealmAllowed(scope, "test-com"));
        assertFalse(ScopeMatcher.isRealmAllowed(scope, "staging-com"));
    }

    @Test
    void excludeRealms_allowsUnlistedRealm() {
        SeedScope scope = new SeedScope();
        scope.setExcludeRealms(List.of("test-com", "staging-com"));
        assertTrue(ScopeMatcher.isRealmAllowed(scope, "production-com"));
    }

    @Test
    void noRealmFilter_allowsAllRealms() {
        SeedScope scope = new SeedScope();
        assertTrue(ScopeMatcher.isRealmAllowed(scope, "any-realm"));
        assertTrue(ScopeMatcher.isRealmAllowed(scope, null));
    }

    @Test
    void backwardCompat_realmsFieldMapsToIncludeRealms() {
        SeedScope scope = new SeedScope();
        scope.setRealms(List.of("legacy-com"));
        assertEquals(List.of("legacy-com"), scope.getIncludeRealms());
        assertTrue(ScopeMatcher.isRealmAllowed(scope, "legacy-com"));
        assertFalse(ScopeMatcher.isRealmAllowed(scope, "other-com"));
    }

    @Test
    void validate_rejectsMutuallyExclusiveLists() {
        SeedScope scope = new SeedScope();
        scope.setIncludeRealms(List.of("a-com"));
        scope.setExcludeRealms(List.of("b-com"));
        assertThrows(IllegalStateException.class, () -> scope.validate("test-manifest"));
    }

    @Test
    void validate_acceptsIncludeOnly() {
        SeedScope scope = new SeedScope();
        scope.setIncludeRealms(List.of("a-com"));
        assertDoesNotThrow(() -> scope.validate("test-manifest"));
    }

    @Test
    void validate_acceptsExcludeOnly() {
        SeedScope scope = new SeedScope();
        scope.setExcludeRealms(List.of("a-com"));
        assertDoesNotThrow(() -> scope.validate("test-manifest"));
    }

    @Test
    void validate_acceptsNeitherSet() {
        SeedScope scope = new SeedScope();
        assertDoesNotThrow(() -> scope.validate("test-manifest"));
    }
}

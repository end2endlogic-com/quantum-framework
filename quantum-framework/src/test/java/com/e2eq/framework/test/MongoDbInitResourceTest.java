package com.e2eq.framework.test;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MongoDbInitResourceTest {

    @Test
    void acceptsTestPrefixedDatabaseName() {
        assertEquals("test-quantum-framework-shared",
                MongoDbInitResource.requireTestDatabaseName(
                        "test-quantum-framework-shared", "test config"));
    }

    @Test
    void rejectsUnscopedDatabaseNameBeforeCleanupCanRun() {
        assertThrows(IllegalArgumentException.class,
                () -> MongoDbInitResource.requireTestDatabaseName(
                        "end2endlogic", "test config"));
        assertThrows(IllegalArgumentException.class,
                () -> MongoDbInitResource.requireTestDatabaseName(
                        "quantum-system", "test config"));
    }

    @Test
    void teardownDropsOnlyConfiguredRealmsAndDeclaredPrefixes() {
        // test-b2bi-com may have been created after this suite started; it is still not ours.
        List<String> owned = MongoDbInitResource.ownedTestDatabases(
                List.of("test-quantum-com", "test-b2bi-com", "test-seed-version-it",
                        "test-usage-9bea3779", "b2bi-com", "admin"),
                Set.of("test-quantum-com", "test-system-quantum-com"),
                List.of("test-seed-", "test-usage-"));

        assertEquals(List.of("test-quantum-com", "test-seed-version-it", "test-usage-9bea3779"), owned);
    }
}

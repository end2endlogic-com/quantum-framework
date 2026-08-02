package com.e2eq.framework.test;

import org.junit.jupiter.api.Test;

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
}

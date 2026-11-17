package com.e2eq.framework.model.persistent.migration.base;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.e2eq.framework.exceptions.DatabaseAheadOfCodeException;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import org.junit.jupiter.api.Test;
import org.semver4j.Semver;

/**
 * The database version a realm holds against the version the code requires
 * ({@code quantum.database.version}): a database a major version ahead is refused, one ahead by
 * a minor or patch version is not.
 */
class MigrationServiceMajorAheadTest {

    private static void check(String database, String required) {
        MigrationService.refuseMajorAhead("acme-orders", Semver.parse(database), Semver.parse(required));
    }

    @Test
    void equalVersionIsAccepted() {
        assertDoesNotThrow(() -> check("1.4.3", "1.4.3"));
    }

    @Test
    void behindIsNotThisChecksRefusal() {
        // "Behind" is refused by the caller with a plain DatabaseMigrationException.
        assertDoesNotThrow(() -> check("1.0.0", "1.4.3"));
        assertDoesNotThrow(() -> check("1.9.9", "2.0.0"));
    }

    @Test
    void aheadByMinorOrPatchIsAccepted() {
        assertDoesNotThrow(() -> check("1.4.4", "1.4.3"));
        assertDoesNotThrow(() -> check("1.9.0", "1.4.3"));
    }

    @Test
    void aheadByMajorIsRefused() {
        DatabaseAheadOfCodeException refused =
            assertThrows(DatabaseAheadOfCodeException.class, () -> check("2.0.0", "1.4.3"));

        assertInstanceOf(DatabaseMigrationException.class, refused,
            "every place that refuses a database that is behind must refuse this too");
        String message = refused.getMessage();
        assertTrue(message.contains("acme-orders"), message);
        assertTrue(message.contains("2.0.0"), message);
        assertTrue(message.contains("1.4.3"), message);
        assertTrue(message.contains("major version ahead"), message);
    }

    @Test
    void aheadBySeveralMajorsIsRefused() {
        assertThrows(DatabaseAheadOfCodeException.class, () -> check("4.1.0", "2.7.5"));
    }
}

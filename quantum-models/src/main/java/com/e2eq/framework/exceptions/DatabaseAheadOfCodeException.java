package com.e2eq.framework.exceptions;

/**
 * The realm's database is a major version ahead of the version this code requires
 * ({@code quantum.database.version}). No migration in this code can fix that: the code is older
 * than the database, so it is refused rather than left to read and write a schema it does not know.
 *
 * <p>A subclass of {@link DatabaseMigrationException} so every place that already refuses a
 * database that is behind refuses this too. Callers that respond to "behind" by running
 * migrations must not do so for this case.
 */
public class DatabaseAheadOfCodeException extends DatabaseMigrationException {

    public DatabaseAheadOfCodeException(String realm, String databaseVersion, String requiredVersion) {
        super(realm, databaseVersion, requiredVersion);
    }

    @Override
    public String getMessage() {
        return "Database for realm " + realm + " is at version " + databaseVersion
            + ", a major version ahead of version " + requiredVersion + " that this code requires."
            + " This code is older than the database and will not run against it:"
            + " deploy code built for database version " + databaseVersion
            + ", or restore the database to a " + majorOf(requiredVersion) + ".x version.";
    }

    private static String majorOf(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }
}

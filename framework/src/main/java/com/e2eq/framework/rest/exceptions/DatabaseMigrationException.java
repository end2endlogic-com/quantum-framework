package com.e2eq.framework.rest.exceptions;






public class DatabaseMigrationException extends RuntimeException  {


    protected String realm;
    protected String databaseVersion;
    protected String requiredVersion;

    public DatabaseMigrationException() {
        super();
    }

    public DatabaseMigrationException(String message) {
        super(message);
    }

    public DatabaseMigrationException(String realm, String databaseVersion, String requiredVersion) {
        super();
        this.realm = realm;
        this.databaseVersion = databaseVersion;
        this.requiredVersion = requiredVersion;
    }

    @Override
    public String getMessage() {
        if (realm != null && databaseVersion != null && requiredVersion != null) {
            return "Database migration required for realm " + realm + " from version " + databaseVersion + " to version " + requiredVersion;
        } else
            return super.getMessage();
    }

}

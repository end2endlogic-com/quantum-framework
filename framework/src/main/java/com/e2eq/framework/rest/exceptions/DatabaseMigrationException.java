package com.e2eq.framework.rest.exceptions;






public class DatabaseMigrationException extends RuntimeException  {


    protected String realm;
    protected String databaseVersion;
    protected String requiredVersion;

    public DatabaseMigrationException(String realm, String databaseVersion, String requiredVersion) {
        super();
        this.realm = realm;
        this.databaseVersion = databaseVersion;
        this.requiredVersion = requiredVersion;
    }

    @Override
    public String getMessage() {
        return "Database migration required for realm " + realm + " from version " + databaseVersion + " to version " + requiredVersion;
    }

}

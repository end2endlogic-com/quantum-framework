package com.e2eq.framework.rest.models;

public enum Role {
    user ("user"),
    admin ("admin"),
    developer ("developer"),
    system ("system"),
    sysadmin("sysadmin");

    String value;

    Role(String v) {
        value = v;
    }

    @Override
    public String toString () {
        return value;
    }
}

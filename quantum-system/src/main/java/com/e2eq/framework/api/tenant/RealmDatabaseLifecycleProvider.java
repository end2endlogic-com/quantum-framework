package com.e2eq.framework.api.tenant;

import java.util.Objects;

/** Provider boundary for a verified dedicated-realm database drop. */
public interface RealmDatabaseLifecycleProvider {

    DropResult dropAndVerify(DropRequest request);

    record DropRequest(String executionRef, String realmId, String databaseName) {
        public DropRequest {
            executionRef = required(executionRef, "executionRef");
            realmId = required(realmId, "realmId");
            databaseName = required(databaseName, "databaseName");
        }
    }

    record DropResult(boolean databaseDropped, boolean absenceVerified) {
        public boolean verified() {
            return databaseDropped && absenceVerified;
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}

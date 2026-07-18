package com.e2eq.framework.api.tenant;

import java.util.Objects;

/**
 * Provider-neutral realm archive boundary used by governed lifecycle workflows.
 * Implementations must create and verify archives before destructive work and
 * must reject restore collisions.
 */
public interface RealmArchiveProvider {

    RealmArchiveManifest createAndVerifyArchive(ArchiveRequest request);

    RealmArchiveManifest inspectArchive(String archiveRef);

    RealmArchiveManifest restoreAndVerify(RestoreRequest request);

    record ArchiveRequest(
        String executionRef,
        String realmId,
        String databaseName,
        String applicationId,
        String tenantId,
        String archiveMountRef
    ) {
        public ArchiveRequest {
            executionRef = required(executionRef, "executionRef");
            realmId = required(realmId, "realmId");
            databaseName = required(databaseName, "databaseName");
            applicationId = required(applicationId, "applicationId");
            archiveMountRef = required(archiveMountRef, "archiveMountRef");
        }
    }

    record RestoreRequest(
        String executionRef,
        String realmId,
        String databaseName,
        String applicationId,
        String tenantId,
        String archiveRef
    ) {
        public RestoreRequest {
            executionRef = required(executionRef, "executionRef");
            realmId = required(realmId, "realmId");
            databaseName = required(databaseName, "databaseName");
            applicationId = required(applicationId, "applicationId");
            archiveRef = required(archiveRef, "archiveRef");
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

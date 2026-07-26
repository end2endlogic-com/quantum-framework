package com.e2eq.framework.system;

import com.e2eq.framework.api.tenant.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class TenantDecommissionWorkflowTest {

    private static final TenantDataScope SCOPE =
        new TenantDataScope("acme", "100", "acme-com", 0);
    private static final Instant PURGE_AFTER =
        Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void pooledWorkflowRevokesArchivesThenSchedulesOneExpirationBatch() {
        List<String> calls = new ArrayList<>();
        TenantDecommissionRequest request = new TenantDecommissionRequest(
            "run-1", "shared-orders", "shared-orders", "orders",
            TenantDeploymentTopology.POOLED_REALM, SCOPE, "archive-store",
            "purge-1", PURGE_AFTER);

        TenantDecommissionResult result = TenantDecommissionWorkflow.execute(
            request,
            archiveProvider(calls),
            revocationProvider(calls),
            null,
            expirationProvider(calls));

        Assertions.assertEquals(List.of("revoke", "tenant-archive", "expire"), calls);
        Assertions.assertFalse(result.databaseDropped());
        Assertions.assertTrue(result.expirationVerified());
        Assertions.assertEquals("purge-1", result.purgeBatchRef());
        Assertions.assertEquals(PURGE_AFTER, result.purgeAfter());
    }

    @Test
    void dedicatedWorkflowRevokesArchivesThenDropsWholeDatabase() {
        List<String> calls = new ArrayList<>();
        TenantDecommissionRequest request = new TenantDecommissionRequest(
            "run-2", "acme-com", "acme-com", "orders",
            TenantDeploymentTopology.DEDICATED_REALM, SCOPE, "archive-store",
            null, null);

        TenantDecommissionResult result = TenantDecommissionWorkflow.execute(
            request,
            archiveProvider(calls),
            revocationProvider(calls),
            dropProvider(calls),
            null);

        Assertions.assertEquals(List.of("revoke", "realm-archive", "drop"), calls);
        Assertions.assertTrue(result.databaseDropped());
        Assertions.assertFalse(result.expirationVerified());
    }

    @Test
    void keepsTenantRevokedButNeverDeletesWhenArchiveVerificationIsIncomplete() {
        List<String> calls = new ArrayList<>();
        TenantDecommissionRequest request = new TenantDecommissionRequest(
            "run-3", "acme-com", "acme-com", "orders",
            TenantDeploymentTopology.DEDICATED_REALM, SCOPE, "archive-store",
            null, null);
        RealmArchiveProvider archiveProvider = archiveProvider(
            calls, manifest(false, false));

        Assertions.assertThrows(IllegalStateException.class, () ->
            TenantDecommissionWorkflow.execute(
                request,
                archiveProvider,
                revocationProvider(calls),
                dropProvider(calls),
                null));
        Assertions.assertEquals(List.of("revoke", "realm-archive"), calls);
    }

    private static RealmArchiveProvider archiveProvider(List<String> calls) {
        return archiveProvider(calls, manifest(true, true));
    }

    private static RealmArchiveProvider archiveProvider(
        List<String> calls,
        RealmArchiveManifest manifest
    ) {
        return new RealmArchiveProvider() {
            @Override
            public RealmArchiveManifest createAndVerifyArchive(ArchiveRequest request) {
                calls.add("realm-archive");
                return manifest;
            }

            @Override
            public RealmArchiveManifest createAndVerifyTenantArchive(
                TenantArchiveRequest request
            ) {
                calls.add("tenant-archive");
                return manifestForRealm("shared-orders", "shared-orders", true, true);
            }

            @Override
            public RealmArchiveManifest inspectArchive(String archiveRef) {
                return manifest;
            }

            @Override
            public RealmArchiveManifest restoreAndVerify(RestoreRequest request) {
                return manifest;
            }
        };
    }

    private static TenantAccessRevocationProvider revocationProvider(
        List<String> calls
    ) {
        return request -> {
            calls.add("revoke");
            return new TenantAccessRevocationProvider.RevocationResult(1, 1, true);
        };
    }

    private static RealmDatabaseLifecycleProvider dropProvider(List<String> calls) {
        return request -> {
            calls.add("drop");
            return new RealmDatabaseLifecycleProvider.DropResult(true, true);
        };
    }

    private static TenantDataExpirationProvider expirationProvider(
        List<String> calls
    ) {
        return request -> {
            calls.add("expire");
            return new TenantDataExpirationProvider.ExpirationManifest(
                request.executionRef(),
                request.realmId(),
                request.tenantScope().tenantId(),
                request.purgeBatchRef(),
                request.purgeAfter(),
                List.of(new TenantDataExpirationProvider.CollectionExpiration(
                    "orders", 12, 12)));
        };
    }

    private static RealmArchiveManifest manifest(
        boolean objectVerified,
        boolean restoreVerified
    ) {
        return manifestForRealm(
            "acme-com", "acme-com", objectVerified, restoreVerified);
    }

    private static RealmArchiveManifest manifestForRealm(
        String realmId,
        String databaseName,
        boolean objectVerified,
        boolean restoreVerified
    ) {
        return new RealmArchiveManifest(
            "archive-1",
            realmId,
            databaseName,
            "orders",
            "acme-com",
            "archive://archive-1",
            "BSON",
            "SHA-256",
            "checksum",
            "key-1",
            100,
            objectVerified,
            restoreVerified,
            Instant.parse("2026-07-26T00:00:00Z"),
            Instant.parse("2026-07-26T00:01:00Z"),
            List.of(new RealmArchiveCollectionManifest("orders", 12, 2)));
    }
}

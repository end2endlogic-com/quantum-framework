package com.e2eq.framework.system;

import com.e2eq.framework.api.tenant.RealmArchiveManifest;
import com.e2eq.framework.api.tenant.RealmArchiveProvider;
import com.e2eq.framework.api.tenant.TenantDataExpirationProvider;
import com.e2eq.framework.api.tenant.TenantDataScope;
import com.e2eq.framework.api.tenant.TenantDecommissionRequest;
import com.e2eq.framework.api.tenant.TenantDecommissionResult;
import com.e2eq.framework.api.tenant.TenantDecommissionStrategy;
import com.e2eq.framework.api.tenant.TenantDecommissionUnavailableException;
import com.e2eq.framework.api.tenant.TenantDeploymentTopology;
import com.e2eq.framework.api.tenant.TenantLifecycle;
import com.e2eq.framework.api.tenant.TenantProvisionRequest;
import com.e2eq.framework.api.tenant.TenantProvisionResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class TenantDecommissionContractTest {

    private static final TenantDataScope SCOPE =
        new TenantDataScope("acme.example", "1000000001", "acme-example", 0);

    @Test
    void dedicatedRequestSelectsArchiveAndDropWithoutPurgeMetadata() {
        TenantDecommissionRequest request = new TenantDecommissionRequest(
            "run-1", "acme-example", "acme-example", "orders",
            TenantDeploymentTopology.DEDICATED_REALM, SCOPE, "archive-store", null, null);

        Assertions.assertEquals(
            TenantDecommissionStrategy.ARCHIVE_AND_DROP_REALM, request.strategy());
    }

    @Test
    void pooledRequestRequiresOnePurgeBatchAndTimestamp() {
        Instant purgeAfter = Instant.parse("2026-08-01T00:00:00Z");
        TenantDecommissionRequest request = new TenantDecommissionRequest(
            "run-2", "shared-orders", "shared-orders", "orders",
            TenantDeploymentTopology.POOLED_REALM, SCOPE, "archive-store",
            "purge-run-2", purgeAfter);

        Assertions.assertEquals(
            TenantDecommissionStrategy.ARCHIVE_AND_EXPIRE_TENANT_DATA, request.strategy());
        Assertions.assertEquals("purge-run-2", request.purgeBatchRef());
        Assertions.assertEquals(purgeAfter, request.purgeAfter());

        Assertions.assertThrows(NullPointerException.class, () ->
            new TenantDecommissionRequest(
                "run-3", "shared-orders", "shared-orders", "orders",
                TenantDeploymentTopology.POOLED_REALM, SCOPE, "archive-store",
                "purge-run-3", null));
    }

    @Test
    void canonicalScopeRejectsIncompleteTenantIdentity() {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new TenantDataScope("acme.example", " ", "acme-example", 0));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> new TenantDataScope("acme.example", "1000000001", "acme-example", -1));
    }

    @Test
    void expirationManifestReconcilesEveryCollection() {
        TenantDataExpirationProvider.ExpirationManifest verified =
            new TenantDataExpirationProvider.ExpirationManifest(
                "run-2", "shared-orders", "acme-example", "purge-run-2",
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(
                    new TenantDataExpirationProvider.CollectionExpiration("orders", 12, 12),
                    new TenantDataExpirationProvider.CollectionExpiration("events", 4, 4)));
        TenantDataExpirationProvider.ExpirationManifest incomplete =
            new TenantDataExpirationProvider.ExpirationManifest(
                "run-2", "shared-orders", "acme-example", "purge-run-2",
                Instant.parse("2026-08-01T00:00:00Z"),
                List.of(new TenantDataExpirationProvider.CollectionExpiration("orders", 12, 11)));

        Assertions.assertTrue(verified.coverageVerified());
        Assertions.assertEquals(16, verified.matchedDocumentCount());
        Assertions.assertEquals(16, verified.stampedDocumentCount());
        Assertions.assertFalse(incomplete.coverageVerified());
        Assertions.assertFalse(new TenantDataExpirationProvider.ExpirationManifest(
            "run-2", "shared-orders", "acme-example", "purge-run-2",
            Instant.parse("2026-08-01T00:00:00Z"), List.of()).coverageVerified());
    }

    @Test
    void pooledResultCannotClaimSharedDatabaseWasDropped() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            new TenantDecommissionResult(
                "run-2", "shared-orders", "acme-example",
                TenantDeploymentTopology.POOLED_REALM,
                TenantDecommissionStrategy.ARCHIVE_AND_EXPIRE_TENANT_DATA,
                "archive-2", true, true, true,
                "purge-run-2", Instant.parse("2026-08-01T00:00:00Z"),
                true, List.of()));
    }

    @Test
    void unimplementedLifecycleAndTenantArchiveFailClosed() {
        TenantLifecycle lifecycle = new TenantLifecycle() {
            @Override
            public TenantProvisionResult provision(TenantProvisionRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.e2eq.framework.api.tenant.TenantDeleteResult delete(String realmId) {
                throw new UnsupportedOperationException();
            }
        };
        RealmArchiveProvider archiveProvider = new RealmArchiveProvider() {
            @Override
            public RealmArchiveManifest createAndVerifyArchive(ArchiveRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RealmArchiveManifest inspectArchive(String archiveRef) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RealmArchiveManifest restoreAndVerify(RestoreRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        TenantDecommissionRequest request = new TenantDecommissionRequest(
            "run-2", "shared-orders", "shared-orders", "orders",
            TenantDeploymentTopology.POOLED_REALM, SCOPE, "archive-store",
            "purge-run-2", Instant.parse("2026-08-01T00:00:00Z"));

        Assertions.assertThrows(
            TenantDecommissionUnavailableException.class,
            () -> lifecycle.decommission(request));
        Assertions.assertThrows(
            TenantDecommissionUnavailableException.class,
            () -> archiveProvider.createAndVerifyTenantArchive(
                new RealmArchiveProvider.TenantArchiveRequest(
                    "run-2", "shared-orders", "shared-orders", "orders",
                    SCOPE, "archive-store")));
    }
}

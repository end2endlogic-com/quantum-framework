package com.e2eq.framework.api.tenant;

import java.util.List;
import java.util.Objects;

/**
 * Strict topology-aware tenant decommission orchestration.
 *
 * <p>The ordering is intentional: revoke and verify access to establish a
 * write fence, archive and verify the stable tenant data, then cross exactly
 * one topology-specific destructive boundary.</p>
 */
public final class TenantDecommissionWorkflow {

    private TenantDecommissionWorkflow() {
    }

    public static TenantDecommissionResult execute(
        TenantDecommissionRequest request,
        RealmArchiveProvider archiveProvider,
        TenantAccessRevocationProvider accessRevocationProvider,
        RealmDatabaseLifecycleProvider databaseLifecycleProvider,
        TenantDataExpirationProvider expirationProvider
    ) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(archiveProvider, "archiveProvider cannot be null");
        Objects.requireNonNull(
            accessRevocationProvider, "accessRevocationProvider cannot be null");

        TenantAccessRevocationProvider.RevocationResult revocation =
            accessRevocationProvider.revokeAndVerify(
                new TenantAccessRevocationProvider.RevocationRequest(
                    request.executionRef(),
                    request.realmId(),
                    request.tenantScope()));
        if (!revocation.verified()) {
            throw new IllegalStateException(
                "Tenant access revocation could not be verified for "
                    + request.tenantScope().tenantId());
        }

        RealmArchiveManifest archive = request.deploymentTopology()
            == TenantDeploymentTopology.DEDICATED_REALM
            ? archiveProvider.createAndVerifyArchive(
                new RealmArchiveProvider.ArchiveRequest(
                    request.executionRef(),
                    request.realmId(),
                    request.databaseName(),
                    request.applicationId(),
                    request.tenantScope().tenantId(),
                    request.archiveMountRef()))
            : archiveProvider.createAndVerifyTenantArchive(
                new RealmArchiveProvider.TenantArchiveRequest(
                    request.executionRef(),
                    request.realmId(),
                    request.databaseName(),
                    request.applicationId(),
                    request.tenantScope(),
                    request.archiveMountRef()));
        verifyArchiveMatchesRequest(request, archive);

        if (request.deploymentTopology() == TenantDeploymentTopology.DEDICATED_REALM) {
            Objects.requireNonNull(
                databaseLifecycleProvider,
                "databaseLifecycleProvider is required for dedicated decommission");
            RealmDatabaseLifecycleProvider.DropResult drop =
                databaseLifecycleProvider.dropAndVerify(
                    new RealmDatabaseLifecycleProvider.DropRequest(
                        request.executionRef(),
                        request.realmId(),
                        request.databaseName()));
            if (!drop.verified()) {
                throw new IllegalStateException(
                    "Dedicated realm database drop could not be verified: "
                        + request.databaseName());
            }
            return new TenantDecommissionResult(
                request.executionRef(),
                request.realmId(),
                request.tenantScope().tenantId(),
                request.deploymentTopology(),
                request.strategy(),
                archive.archiveRef(),
                true,
                true,
                true,
                null,
                null,
                false,
                List.of());
        }

        Objects.requireNonNull(
            expirationProvider,
            "expirationProvider is required for pooled decommission");
        TenantDataExpirationProvider.ExpirationManifest expiration =
            expirationProvider.stampAndVerify(
                new TenantDataExpirationProvider.ExpirationRequest(
                    request.executionRef(),
                    request.realmId(),
                    request.databaseName(),
                    request.applicationId(),
                    request.tenantScope(),
                    request.purgeBatchRef(),
                    request.purgeAfter()));
        if (!expiration.coverageVerified()
            || !request.purgeBatchRef().equals(expiration.purgeBatchRef())
            || !request.purgeAfter().equals(expiration.purgeAfter())
            || !request.tenantScope().tenantId().equals(expiration.tenantId())) {
            throw new IllegalStateException(
                "Pooled tenant expiration evidence does not match the request");
        }
        return new TenantDecommissionResult(
            request.executionRef(),
            request.realmId(),
            request.tenantScope().tenantId(),
            request.deploymentTopology(),
            request.strategy(),
            archive.archiveRef(),
            true,
            true,
            false,
            request.purgeBatchRef(),
            request.purgeAfter(),
            true,
            List.of());
    }

    private static void verifyArchiveMatchesRequest(
        TenantDecommissionRequest request,
        RealmArchiveManifest archive
    ) {
        Objects.requireNonNull(archive, "archive provider returned null");
        if (!archive.objectVerified()
            || !archive.restoreRehearsalVerified()
            || !request.realmId().equals(archive.realmId())
            || !request.databaseName().equals(archive.databaseName())
            || !request.applicationId().equals(archive.applicationId())
            || !request.tenantScope().tenantId().equals(archive.tenantId())) {
            throw new IllegalStateException(
                "Archive verification evidence does not match the decommission request");
        }
    }
}

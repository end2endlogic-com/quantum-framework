package com.e2eq.framework.api.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evidence returned only after a decommission workflow has crossed its safe
 * topology-specific boundary.
 */
public record TenantDecommissionResult(
    String executionRef,
    String realmId,
    String tenantId,
    TenantDeploymentTopology deploymentTopology,
    TenantDecommissionStrategy strategy,
    String archiveRef,
    boolean archiveVerified,
    boolean tenantAccessRevoked,
    boolean databaseDropped,
    String purgeBatchRef,
    Instant purgeAfter,
    boolean expirationVerified,
    List<String> warnings
) {
    public TenantDecommissionResult {
        executionRef = required(executionRef, "executionRef");
        realmId = required(realmId, "realmId");
        tenantId = required(tenantId, "tenantId");
        deploymentTopology = Objects.requireNonNull(
            deploymentTopology, "deploymentTopology cannot be null");
        strategy = Objects.requireNonNull(strategy, "strategy cannot be null");
        archiveRef = required(archiveRef, "archiveRef");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);

        TenantDecommissionStrategy expected = deploymentTopology
            == TenantDeploymentTopology.DEDICATED_REALM
            ? TenantDecommissionStrategy.ARCHIVE_AND_DROP_REALM
            : TenantDecommissionStrategy.ARCHIVE_AND_EXPIRE_TENANT_DATA;
        if (strategy != expected) {
            throw new IllegalArgumentException(
                "strategy " + strategy + " does not match " + deploymentTopology);
        }
        if (!archiveVerified || !tenantAccessRevoked) {
            throw new IllegalArgumentException(
                "successful decommission requires verified archive and revoked tenant access");
        }
        if (deploymentTopology == TenantDeploymentTopology.DEDICATED_REALM) {
            if (!databaseDropped) {
                throw new IllegalArgumentException(
                    "dedicated decommission requires the database to be dropped");
            }
            if (purgeBatchRef != null || purgeAfter != null || expirationVerified) {
                throw new IllegalArgumentException(
                    "dedicated decommission cannot contain pooled expiration evidence");
            }
        } else {
            purgeBatchRef = required(purgeBatchRef, "purgeBatchRef");
            Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null");
            if (databaseDropped) {
                throw new IllegalArgumentException(
                    "pooled decommission must not drop the shared database");
            }
            if (!expirationVerified) {
                throw new IllegalArgumentException(
                    "pooled decommission requires verified expiration coverage");
            }
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

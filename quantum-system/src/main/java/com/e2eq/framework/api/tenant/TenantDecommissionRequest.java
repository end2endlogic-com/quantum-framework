package com.e2eq.framework.api.tenant;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider-neutral request for governed tenant decommissioning.
 *
 * <p>Dedicated realms are archived and dropped as one lifecycle unit. Pooled
 * tenants are archived by canonical {@link TenantDataScope}; all matching
 * rows are then assigned the same purge batch and expiration timestamp. Mongo
 * TTL cleanup is asynchronous, so the workflow must revoke tenant access
 * before scheduling expiration.</p>
 */
public record TenantDecommissionRequest(
    String executionRef,
    String realmId,
    String databaseName,
    String applicationId,
    TenantDeploymentTopology deploymentTopology,
    TenantDataScope tenantScope,
    String archiveMountRef,
    String purgeBatchRef,
    Instant purgeAfter
) {
    public TenantDecommissionRequest {
        executionRef = required(executionRef, "executionRef");
        realmId = required(realmId, "realmId");
        databaseName = required(databaseName, "databaseName");
        applicationId = required(applicationId, "applicationId");
        deploymentTopology = Objects.requireNonNull(
            deploymentTopology, "deploymentTopology cannot be null");
        tenantScope = Objects.requireNonNull(tenantScope, "tenantScope cannot be null");
        archiveMountRef = required(archiveMountRef, "archiveMountRef");

        if (deploymentTopology == TenantDeploymentTopology.POOLED_REALM) {
            purgeBatchRef = required(purgeBatchRef, "purgeBatchRef");
            Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null for pooled decommission");
        } else if (purgeBatchRef != null || purgeAfter != null) {
            throw new IllegalArgumentException(
                "purgeBatchRef and purgeAfter are only valid for pooled decommission");
        }
    }

    public TenantDecommissionStrategy strategy() {
        return deploymentTopology == TenantDeploymentTopology.DEDICATED_REALM
            ? TenantDecommissionStrategy.ARCHIVE_AND_DROP_REALM
            : TenantDecommissionStrategy.ARCHIVE_AND_EXPIRE_TENANT_DATA;
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}

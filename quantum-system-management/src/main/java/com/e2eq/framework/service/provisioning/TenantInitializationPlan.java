package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.model.persistent.migration.base.MigrationPlan;
import com.e2eq.framework.service.seed.SeedPlan;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/** Immutable non-secret initialization snapshot stored on the existing provisioning run. */
@RegisterForReflection
public record TenantInitializationPlan(int schemaVersion, String runtimeArtifactSha256,
        String applicationId, String realmId, String tenantId, String deploymentTopology,
        MigrationPlan migrations, SeedPlan baseSeeds, List<Archetype> archetypes) {
    public TenantInitializationPlan {
        if (schemaVersion != 1 || runtimeArtifactSha256 == null || !runtimeArtifactSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("PROVISIONING_PLAN_IDENTITY_INVALID");
        if (applicationId == null || applicationId.isBlank() || realmId == null || realmId.isBlank()
                || tenantId == null || tenantId.isBlank())
            throw new IllegalArgumentException("PROVISIONING_PLAN_SCOPE_REQUIRED");
        archetypes = List.copyOf(archetypes);
        if ("DEDICATED_REALM".equals(deploymentTopology)) {
            if (migrations == null || baseSeeds == null || !realmId.equals(migrations.realm()))
                throw new IllegalArgumentException("PROVISIONING_PLAN_INITIALIZATION_REQUIRED");
        } else if ("POOLED_REALM".equals(deploymentTopology)) {
            if (migrations != null || baseSeeds != null || !archetypes.isEmpty())
                throw new IllegalArgumentException("PROVISIONING_PLAN_POOL_BOOTSTRAP_FORBIDDEN");
        } else throw new IllegalArgumentException("PROVISIONING_PLAN_TOPOLOGY_INVALID");
    }

    @RegisterForReflection
    public record Archetype(String name, SeedPlan seeds) {
        public Archetype {
            if (name == null || name.isBlank() || seeds == null)
                throw new IllegalArgumentException("PROVISIONING_PLAN_ARCHETYPE_INVALID");
        }
    }
}

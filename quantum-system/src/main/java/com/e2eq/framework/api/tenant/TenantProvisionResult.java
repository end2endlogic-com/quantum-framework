package com.e2eq.framework.api.tenant;

import java.util.List;

/** Control-plane contract: outcome of a tenant provisioning request. */
public final class TenantProvisionResult {

    private final String realmId;
    private final String tenantId;
    private final TenantDeploymentTopology deploymentTopology;
    private final boolean realmCreated;
    private final boolean userCreated;
    private final List<String> appliedSeedArchetypes;
    private final List<String> warnings;

    public TenantProvisionResult(String realmId,
                                 boolean realmCreated,
                                 boolean userCreated,
                                 List<String> appliedSeedArchetypes,
                                 List<String> warnings) {
        this(realmId, null, null, realmCreated, userCreated, appliedSeedArchetypes, warnings);
    }

    public TenantProvisionResult(String realmId,
                                 String tenantId,
                                 TenantDeploymentTopology deploymentTopology,
                                 boolean realmCreated,
                                 boolean userCreated,
                                 List<String> appliedSeedArchetypes,
                                 List<String> warnings) {
        this.realmId = realmId;
        this.tenantId = tenantId;
        this.deploymentTopology = deploymentTopology;
        this.realmCreated = realmCreated;
        this.userCreated = userCreated;
        this.appliedSeedArchetypes = appliedSeedArchetypes == null ? List.of() : List.copyOf(appliedSeedArchetypes);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String realmId() { return realmId; }
    public String tenantId() { return tenantId; }
    public TenantDeploymentTopology deploymentTopology() { return deploymentTopology; }
    public boolean realmCreated() { return realmCreated; }
    public boolean userCreated() { return userCreated; }
    public List<String> appliedSeedArchetypes() { return appliedSeedArchetypes; }
    public List<String> warnings() { return warnings; }
}

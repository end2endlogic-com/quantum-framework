package com.e2eq.framework.api.tenant;

import java.util.List;

/** Control-plane contract: outcome of a tenant provisioning request. */
public final class TenantProvisionResult {

    private final String realmId;
    private final boolean realmCreated;
    private final boolean userCreated;
    private final List<String> appliedSeedArchetypes;
    private final List<String> warnings;

    public TenantProvisionResult(String realmId,
                                 boolean realmCreated,
                                 boolean userCreated,
                                 List<String> appliedSeedArchetypes,
                                 List<String> warnings) {
        this.realmId = realmId;
        this.realmCreated = realmCreated;
        this.userCreated = userCreated;
        this.appliedSeedArchetypes = appliedSeedArchetypes == null ? List.of() : List.copyOf(appliedSeedArchetypes);
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String realmId() { return realmId; }
    public boolean realmCreated() { return realmCreated; }
    public boolean userCreated() { return userCreated; }
    public List<String> appliedSeedArchetypes() { return appliedSeedArchetypes; }
    public List<String> warnings() { return warnings; }
}

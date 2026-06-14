package com.e2eq.framework.api.tenant;

import java.util.List;

/** Control-plane contract: outcome of a tenant deletion. */
public final class TenantDeleteResult {

    private final String realmId;
    private final boolean realmCatalogDeleted;
    private final boolean databaseDropped;
    private final int deletedCredentialCount;
    private final List<String> warnings;

    public TenantDeleteResult(String realmId,
                              boolean realmCatalogDeleted,
                              boolean databaseDropped,
                              int deletedCredentialCount,
                              List<String> warnings) {
        this.realmId = realmId;
        this.realmCatalogDeleted = realmCatalogDeleted;
        this.databaseDropped = databaseDropped;
        this.deletedCredentialCount = deletedCredentialCount;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String realmId() { return realmId; }
    public boolean realmCatalogDeleted() { return realmCatalogDeleted; }
    public boolean databaseDropped() { return databaseDropped; }
    public int deletedCredentialCount() { return deletedCredentialCount; }
    public List<String> warnings() { return warnings; }
}

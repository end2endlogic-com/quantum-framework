package com.e2eq.framework.model.persistent.migration.base;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/** Ordered exact migration catalog for one realm. Persist before execution. */
@RegisterForReflection
public record MigrationPlan(int schemaVersion, String realm, String targetVersion,
                            String databaseScope, List<Entry> entries) {
    public MigrationPlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("MIGRATION_PLAN_VERSION_UNSUPPORTED");
        if (realm == null || realm.isBlank() || targetVersion == null || targetVersion.isBlank())
            throw new IllegalArgumentException("MIGRATION_PLAN_IDENTITY_REQUIRED");
        entries = List.copyOf(entries);
    }

    @RegisterForReflection
    public record Entry(String name, int version, String definitionSha256) {
        public Entry {
            if (name == null || name.isBlank() || version < 1 || definitionSha256 == null
                    || !definitionSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("MIGRATION_PLAN_ENTRY_INVALID");
        }
    }
}

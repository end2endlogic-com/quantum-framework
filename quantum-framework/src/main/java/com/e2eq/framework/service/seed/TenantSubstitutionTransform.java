package com.e2eq.framework.service.seed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Transform;

/**
 * Injects tenant scoped values (tenantId, orgRefName, ownerId, accountId, realmId) into each record.
 */
public final class TenantSubstitutionTransform implements SeedTransform {

    private final String tenantField;
    private final String orgField;
    private final String ownerField;
    private final String accountField;
    private final String realmField;
    private final boolean overwrite;

    public TenantSubstitutionTransform(String tenantField,
                                       String orgField,
                                       String ownerField,
                                       String accountField,
                                       String realmField,
                                       boolean overwrite) {
        this.tenantField = tenantField;
        this.orgField = orgField;
        this.ownerField = ownerField;
        this.accountField = accountField;
        this.realmField = realmField;
        this.overwrite = overwrite;
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> record, SeedContext context, Dataset dataset) {
        Map<String, Object> mutated = new LinkedHashMap<>(record);
        context.getTenantId().ifPresent(value -> maybePut(mutated, tenantField, value));
        context.getOrgRefName().ifPresent(value -> maybePut(mutated, orgField, value));
        context.getOwnerId().ifPresent(value -> maybePut(mutated, ownerField, value));
        context.getAccountId().ifPresent(value -> maybePut(mutated, accountField, value));
        maybePut(mutated, realmField, context.getRealmId());
        return mutated;
    }

    private void maybePut(Map<String, Object> map, String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        if (!overwrite && map.containsKey(key)) {
            return;
        }
        map.put(key, value);
    }

    public static TenantSubstitutionTransform from(Transform transform) {
        Map<String, Object> config = transform.getConfig();
        String tenantField = asString(config.getOrDefault("tenantField", "tenantId"));
        String orgField = asString(config.getOrDefault("orgField", "orgRefName"));
        String ownerField = asString(config.getOrDefault("ownerField", "ownerId"));
        String accountField = asString(config.getOrDefault("accountField", "accountId"));
        String realmField = asString(config.getOrDefault("realmField", "realmId"));
        boolean overwrite = Boolean.parseBoolean(String.valueOf(config.getOrDefault("overwrite", Boolean.TRUE)));
        return new TenantSubstitutionTransform(tenantField, orgField, ownerField, accountField, realmField, overwrite);
    }

    private static String asString(Object value) {
        return value == null ? null : Objects.toString(value);
    }

    public static final class Factory implements SeedTransformFactory {
        @Override
        public SeedTransform create(Transform transformDefinition) {
            return TenantSubstitutionTransform.from(transformDefinition);
        }
    }
}

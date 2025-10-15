package com.e2eq.framework.service.seed;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Transform;

/**
 * Injects tenant scoped values (tenantId, orgRefName, ownerId, accountNum, realmId) into each record.
 *
 * For UnversionedBaseModel-based entities, tenant/org/owner/account are written into the nested
 * "dataDomain" object on the record. The realmId is written at the record root under the configured
 * realmField (default: "realmId").
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

        // Ensure nested dataDomain map exists where tenant/org/owner/account live
        final Map<String, Object> dataDomain = ensureDataDomain(mutated);

        context.getTenantId().ifPresent(value -> maybePut(dataDomain, tenantField, value));
        context.getOrgRefName().ifPresent(value -> maybePut(dataDomain, orgField, value));
        context.getOwnerId().ifPresent(value -> maybePut(dataDomain, ownerField, value));
        context.getAccountId().ifPresent(value -> maybePut(dataDomain, accountField, value));

        // realmId is not part of DataDomain; keep it at the root
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureDataDomain(Map<String, Object> root) {
        Object existing = root.get("dataDomain");
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> dd = new LinkedHashMap<>();
        root.put("dataDomain", dd);
        return dd;
    }

    public static TenantSubstitutionTransform from(Transform transform) {
        Map<String, Object> config = transform.getConfig();
        String tenantField = asString(config.getOrDefault("tenantField", "tenantId"));
        String orgField = asString(config.getOrDefault("orgField", "orgRefName"));
        String ownerField = asString(config.getOrDefault("ownerField", "ownerId"));
        String accountField = asString(config.getOrDefault("accountField", "accountNum"));
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

package com.e2eq.framework.service.seed;

import java.util.Map;
import java.util.Objects;

/**
 * Drops a record when the configured field matches the configured value.
 */
public final class DropIfTransform implements SeedTransform {
    private final String field;
    private final String equalsValue;

    public DropIfTransform(String field, String equalsValue) {
        this.field = field;
        this.equalsValue = equalsValue;
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> record, SeedContext context, SeedPackManifest.Dataset dataset) {
        Object value = record.get(field);
        if (Objects.equals(Objects.toString(value, null), equalsValue)) {
            return null;
        }
        return record;
    }

    public static final class Factory implements SeedTransformFactory {
        @Override
        public SeedTransform create(SeedPackManifest.Transform transformDefinition) {
            Map<String, Object> cfg = transformDefinition.getConfig();
            String field = Objects.toString(cfg.get("field"), null);
            String eq = Objects.toString(cfg.get("equals"), null);
            return new DropIfTransform(field, eq);
        }
    }
}

package com.e2eq.framework.service.seed.testtransforms;

import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedTransform;
import com.e2eq.framework.service.seed.SeedTransformFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Test-only transform that drops a record when field equals a given value.
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
        Object v = record.get(field);
        if (Objects.equals(Objects.toString(v, null), equalsValue)) {
            return null; // short-circuit/drop
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

package com.e2eq.framework.service.seed.testtransforms;

import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedPackManifest;
import com.e2eq.framework.service.seed.SeedTransform;
import com.e2eq.framework.service.seed.SeedTransformFactory;

import java.util.Map;
import java.util.Objects;

/** Test-only transform that removes a field from the record. */
public final class RemoveFieldTransform implements SeedTransform {
    private final String field;
    public RemoveFieldTransform(String field) { this.field = field; }

    @Override
    public Map<String, Object> apply(Map<String, Object> record, SeedContext context, SeedPackManifest.Dataset dataset) {
        record.remove(field);
        return record;
    }

    public static final class Factory implements SeedTransformFactory {
        @Override
        public SeedTransform create(SeedPackManifest.Transform transformDefinition) {
            String field = Objects.toString(transformDefinition.getConfig().get("field"), null);
            return new RemoveFieldTransform(field);
        }
    }
}

package com.e2eq.framework.service.seed;

import com.e2eq.framework.service.seed.SeedPackManifest.Transform;

/**
 * Factory for dataset scoped transforms declared in a manifest.
 */
@FunctionalInterface
public interface SeedTransformFactory {

    SeedTransform create(Transform transformDefinition);
}

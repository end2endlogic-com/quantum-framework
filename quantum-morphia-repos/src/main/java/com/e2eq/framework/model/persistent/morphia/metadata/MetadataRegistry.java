package com.e2eq.framework.model.persistent.morphia.metadata;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;

/**
 * Mongo-focused metadata registry (v1) to resolve expand paths and validate projections.
 * This is a scaffold; concrete resolution will be implemented incrementally.
 */
public interface MetadataRegistry {
    ResolvedPath resolvePath(Class<? extends UnversionedBaseModel> rootType, String dottedPath);
    JoinSpec resolveJoin(Class<? extends UnversionedBaseModel> rootType, String dottedPath);
    void validateProjectionPaths(Class<?> scopeType, ProjectionSpec projection) throws QueryMetadataException;
    boolean isKnownTypeName(String typeName);
}

package com.e2eq.framework.model.persistent.morphia.metadata;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

/**
 * Default Mongo-focused MetadataRegistry (v1).
 *
 * This implementation is intentionally conservative:
 * - Resolves simple expand paths against the root model via reflection
 * - Detects EntityReference hops (single or arrays/collections)
 * - Computes a basic JoinSpec with local id expression and array flag
 * - Collection name and tenant field are left null for now (will be enriched later)
 */
public class DefaultMetadataRegistry implements MetadataRegistry {

    @Override
    public ResolvedPath resolvePath(Class<? extends UnversionedBaseModel> rootType, String dottedPath) {
        if (rootType == null || dottedPath == null || dottedPath.isBlank()) {
            throw new QueryMetadataException("rootType and path are required");
        }
        List<PathSegment> segments = parseSegments(dottedPath);
        Class<?> current = rootType;
        boolean isArray = false;
        boolean isReference = false;
        boolean stoppedAtBoundary = false;

        for (int i = 0; i < segments.size(); i++) {
            PathSegment seg = segments.get(i);
            Field f = findField(current, seg.name);
            if (f == null) {
                // Unknown field on current type
                throw new QueryMetadataException("Unknown path segment '" + seg.name + "' on type " + current.getName());
            }
            Class<?> fieldType = f.getType();
            boolean fieldIsArray = fieldType.isArray() || java.util.Collection.class.isAssignableFrom(fieldType);
            isArray = isArray || seg.array || fieldIsArray;

            // Determine element type if collection/array
            Class<?> elementType = fieldType;
            if (fieldType.isArray()) {
                elementType = fieldType.getComponentType();
            } else if (java.util.Collection.class.isAssignableFrom(fieldType)) {
                var g = f.getGenericType();
                if (g instanceof ParameterizedType pt) {
                    var args = pt.getActualTypeArguments();
                    if (args.length == 1 && args[0] instanceof Class<?> cArg) {
                        elementType = cArg;
                    }
                }
            }

            if (EntityReference.class.isAssignableFrom(elementType)) {
                // We discovered a reference hop
                isReference = true;
                current = elementType; // continue, though in v1 we stop at first reference for joins
            } else {
                // Not an EntityReference; we mark boundary and stop traversing for join semantics
                stoppedAtBoundary = true;
                current = elementType;
            }
        }

        return new ResolvedPath(current, isArray, isReference, stoppedAtBoundary, segments);
    }

    @Override
    public JoinSpec resolveJoin(Class<? extends UnversionedBaseModel> rootType, String dottedPath) {
        ResolvedPath rp = resolvePath(rootType, dottedPath);
        if (!rp.isReference) {
            throw new QueryMetadataException("expand path '" + dottedPath + "' is not an EntityReference");
        }
        String localIdExpr = dottedPath + ".entityId"; // convention for EntityReference
        // Attempt to resolve collection from @ReferenceTarget on the final field
        String fromCollection = resolveCollectionFromAnnotation(rootType, dottedPath);
        return new JoinSpec(fromCollection, /*remoteIdField*/ "_id", localIdExpr, /*tenantField*/ "dataDomain.tenantId", rp.isArray);
    }

    private String resolveCollectionFromAnnotation(Class<? extends UnversionedBaseModel> rootType, String dottedPath) {
        try {
            Field f = findFieldAlongPath(rootType, dottedPath);
            if (f == null) return null;
            var ann = f.getAnnotation(com.e2eq.framework.model.persistent.base.ReferenceTarget.class);
            if (ann == null) return null;
            if (!ann.collection().isBlank()) return ann.collection();
            Class<?> target = ann.target();
            // Check Morphia @Entity value
            dev.morphia.annotations.Entity e = target.getAnnotation(dev.morphia.annotations.Entity.class);
            if (e != null && e.value() != null && !e.value().isBlank()) {
                return e.value();
            }
            return target.getSimpleName();
        } catch (Exception ex) {
            return null;
        }
    }

    private Field findFieldAlongPath(Class<?> type, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Class<?> current = type;
        Field last = null;
        for (String raw : parts) {
            String name = raw.replace("[*]", "");
            last = findField(current, name);
            if (last == null) return null;
            Class<?> fieldType = last.getType();
            if (fieldType.isArray()) {
                current = fieldType.getComponentType();
            } else if (java.util.Collection.class.isAssignableFrom(fieldType)) {
                var g = last.getGenericType();
                if (g instanceof java.lang.reflect.ParameterizedType pt) {
                    var args = pt.getActualTypeArguments();
                    if (args.length == 1 && args[0] instanceof Class<?> cArg) {
                        current = cArg;
                        continue;
                    }
                }
                current = Object.class;
            } else {
                current = fieldType;
            }
        }
        return last;
    }

    @Override
    public void validateProjectionPaths(Class<?> scopeType, ProjectionSpec projection) throws QueryMetadataException {
        if (projection == null) return;
        // For v1: perform shallow validation on the presence of the first segment fields
        for (String p : projection.include) {
            validateFirstSegment(scopeType, p);
        }
        for (String p : projection.exclude) {
            validateFirstSegment(scopeType, p);
        }
    }

    @Override
    public boolean isKnownTypeName(String typeName) {
        // v1 does not support aliases; accept only fully-qualified class names that exist
        if (typeName == null || typeName.isBlank()) return false;
        try {
            Class.forName(typeName);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void validateFirstSegment(Class<?> scopeType, String path) {
        if (path == null || path.isBlank()) return;
        String first = path;
        int i = path.indexOf('.') ;
        if (i >= 0) first = path.substring(0, i);
        // strip any [*]
        first = first.replace("[*]", "");
        Field f = findField(scopeType, first);
        if (f == null) {
            throw new QueryMetadataException("Unknown projection path segment '" + first + "' on type " + scopeType.getName());
        }
    }

    private Field findField(Class<?> type, String name) {
        String clean = name.replace("[*]", "");
        Class<?> t = type;
        while (t != null && t != Object.class) {
            try {
                Field f = t.getDeclaredField(clean);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                t = t.getSuperclass();
            }
        }
        return null;
    }

    private List<PathSegment> parseSegments(String path) {
        String[] parts = path.split("\\.");
        List<PathSegment> list = new ArrayList<>();
        for (String p : parts) {
            boolean arr = p.endsWith("[*]");
            String name = arr ? p.substring(0, p.length() - 3) : p;
            list.add(new PathSegment(name, arr));
        }
        return list;
    }
}

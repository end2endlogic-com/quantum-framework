package com.e2eq.framework.model.securityrules;

import java.lang.reflect.Field;
import java.util.Collection;

/**
 * Datasource-agnostic enforcement of field-level policy (Rule.excludedFields).
 *
 * The policy SOURCE is resolved once per request (RuleContext
 * getExcludedFieldPaths — the deny-wins union over matched ALLOW rules); this
 * utility is the enforcement primitive every egress shares. The query language
 * is a multi-backend contract, so enforcement is layered per egress:
 *
 *   - Mongo finds: projection injection (MorphiaRepo.buildFindOptions) — the
 *     data never leaves the database; this class is not involved.
 *   - Mongo updates: preserve-hidden-fields gate (MorphiaRepo.merge) uses
 *     {@link #copyPath} to restore stored values over blind overwrites.
 *   - In-memory / hierarchical / custom datasource adapters: {@link #mask}
 *     on materialized results — for these backends this IS the
 *     datastore-level enforcement, not a backstop.
 *   - REST serialization: the response interceptor applies {@link #mask} as
 *     defense-in-depth (computed getters, expanded references, any path that
 *     bypassed a repo).
 *
 * Implementing the query contract against a new backend REQUIRES wiring this
 * enforcer (see permissions.adoc, "Field-level policy").
 *
 * Masking semantics: excluded paths are set to null on the materialized
 * object (with the platform's NON_NULL serialization defaults they are omitted
 * from JSON). Dotted paths descend embedded objects; collections are masked
 * element-wise. Unknown paths are ignored (nothing to protect on this type).
 */
public final class FieldPolicyEnforcer {

    private FieldPolicyEnforcer() {
    }

    /** Null out every excluded path on the object (or each element of a collection). */
    public static void mask(Object root, Collection<String> dottedPaths) {
        if (root == null || dottedPaths == null || dottedPaths.isEmpty()) {
            return;
        }
        if (root instanceof Collection<?> many) {
            for (Object item : many) {
                mask(item, dottedPaths);
            }
            return;
        }
        for (String path : dottedPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                maskPath(root, path.trim());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Field-level policy could not mask path '" + path + "' on "
                    + root.getClass().getSimpleName() + "; failing closed.", e);
            }
        }
    }

    private static void maskPath(Object target, String dottedPath) throws ReflectiveOperationException {
        String[] parts = dottedPath.split("\\.", 2);
        Field field = findField(target.getClass(), parts[0]);
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (parts.length == 1) {
            field.set(target, null);
            return;
        }
        Object child = field.get(target);
        if (child == null) {
            return;
        }
        if (child instanceof Collection<?> many) {
            for (Object item : many) {
                if (item != null) {
                    maskPath(item, parts[1]);
                }
            }
            return;
        }
        maskPath(child, parts[1]);
    }

    /**
     * Copy the value at a dotted path from {@code source} onto {@code target}
     * (the write-gate primitive: restores a stored value over an incoming
     * overwrite of a policy-hidden field).
     */
    public static void copyPath(Object source, Object target, String dottedPath) throws ReflectiveOperationException {
        String[] parts = dottedPath.split("\\.", 2);
        Field field = findField(source.getClass(), parts[0]);
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (parts.length == 1) {
            field.set(target, field.get(source));
            return;
        }
        Object sourceChild = field.get(source);
        Object targetChild = field.get(target);
        if (sourceChild == null || targetChild == null) {
            field.set(target, sourceChild);
            return;
        }
        copyPath(sourceChild, targetChild, parts[1]);
    }

    static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}

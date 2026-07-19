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
 * element-wise. Invalid configured paths fail closed because silently ignoring
 * a policy/schema mismatch would expose data.
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
            throw new NoSuchFieldException("Policy path '" + dottedPath
                + "' does not exist on " + target.getClass().getName());
        }
        field.setAccessible(true);
        if (parts.length == 1) {
            field.set(target, field.getType().isPrimitive()
                    ? primitiveDefaultValue(field.getType())
                    : null);
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
            throw new NoSuchFieldException("Policy path '" + dottedPath
                + "' does not exist on " + source.getClass().getName());
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
        if (sourceChild instanceof Collection<?>) {
            // Collection elements may be reordered or keyed independently. Preserving the
            // complete stored collection is the only fail-closed generic operation without
            // an explicit element identity contract.
            field.set(target, sourceChild);
            return;
        }
        copyPath(sourceChild, targetChild, parts[1]);
    }

    /**
     * Rejects a create payload that supplies any field hidden by policy.
     *
     * <p>Silently clearing a submitted value makes an unauthorized write look
     * successful. Creates therefore fail explicitly; trusted seed/migration code
     * must use an explicit {@link SecurityCallScope#openIgnoringRules()} boundary.</p>
     */
    public static void assertUnset(Object root, Collection<String> dottedPaths) {
        if (root == null || dottedPaths == null || dottedPaths.isEmpty()) {
            return;
        }
        for (String path : dottedPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                if (isSet(root, path.trim())) {
                    throw new SecurityException(
                        "Create payload supplies field '" + path + "' protected by field-level policy");
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Field-level policy could not validate create path '" + path + "' on "
                    + root.getClass().getSimpleName() + "; failing closed.", e);
            }
        }
    }

    private static boolean isSet(Object target, String dottedPath) throws ReflectiveOperationException {
        if (target == null) {
            return false;
        }
        if (target instanceof Collection<?> many) {
            for (Object item : many) {
                if (isSet(item, dottedPath)) {
                    return true;
                }
            }
            return false;
        }
        String[] parts = dottedPath.split("\\.", 2);
        Field field = findField(target.getClass(), parts[0]);
        if (field == null) {
            throw new NoSuchFieldException("Policy path '" + dottedPath
                + "' does not exist on " + target.getClass().getName());
        }
        field.setAccessible(true);
        Object value = field.get(target);
        if (parts.length == 1) {
            return value != null && !isPrimitiveDefault(field.getType(), value);
        }
        return value != null && isSet(value, parts[1]);
    }

    private static boolean isPrimitiveDefault(Class<?> type, Object value) {
        if (!type.isPrimitive()) {
            return false;
        }
        if (type == boolean.class) {
            return Boolean.FALSE.equals(value);
        }
        if (type == char.class) {
            return Character.valueOf('\0').equals(value);
        }
        return value instanceof Number number && number.doubleValue() == 0D;
    }

    private static Object primitiveDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new IllegalArgumentException("Unsupported primitive field type: " + type.getName());
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

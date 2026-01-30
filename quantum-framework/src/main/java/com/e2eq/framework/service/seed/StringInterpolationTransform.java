package com.e2eq.framework.service.seed;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.e2eq.framework.service.seed.SeedPackManifest.Dataset;
import com.e2eq.framework.service.seed.SeedPackManifest.Transform;

import io.quarkus.logging.Log;

/**
 * Performs string interpolation on seed record fields, replacing {@code {variableName}}
 * placeholders with resolved values.
 *
 * <p>This transform walks through all string fields in a record (including nested maps and lists)
 * and replaces variable references with their resolved values.</p>
 *
 * <p>Variable syntax: {@code {variableName}}</p>
 *
 * <p>Example seed data:
 * <pre>
 * {
 *   "runAsUserId": "admin@{tenantId}",
 *   "realm": "{realm}",
 *   "config": {
 *     "owner": "{ownerId}"
 *   }
 * }
 * </pre>
 * </p>
 *
 * <h3>Configuration options:</h3>
 * <ul>
 *   <li>{@code fields} (optional) - List of field names to interpolate. If not specified,
 *       all string fields are processed.</li>
 *   <li>{@code failOnMissing} (optional, default: false) - If true, throws an exception when
 *       a variable cannot be resolved. If false, unresolved variables are left as-is.</li>
 * </ul>
 *
 * <h3>Manifest example:</h3>
 * <pre>
 * transforms:
 *   - type: stringInterpolation
 *     config:
 *       fields: ["runAsUserId", "realm"]
 *       failOnMissing: true
 * </pre>
 *
 * @see SeedVariableResolver
 */
public final class StringInterpolationTransform implements SeedTransform {

    // Pattern matches {variableName} - variable names can contain alphanumerics, underscores, and dots
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_.]*)}");

    private final Set<String> targetFields;
    private final boolean failOnMissing;
    private final List<SeedVariableResolver> resolvers;

    public StringInterpolationTransform(Set<String> targetFields,
                                        boolean failOnMissing,
                                        List<SeedVariableResolver> resolvers) {
        this.targetFields = targetFields != null ? Set.copyOf(targetFields) : null;
        this.failOnMissing = failOnMissing;
        // Sort resolvers by priority (highest first)
        List<SeedVariableResolver> sorted = new ArrayList<>(resolvers);
        sorted.sort(Comparator.comparingInt(SeedVariableResolver::priority).reversed());
        this.resolvers = List.copyOf(sorted);
    }

    @Override
    public Map<String, Object> apply(Map<String, Object> record, SeedContext context, Dataset dataset) {
        Map<String, Object> mutated = new LinkedHashMap<>(record);
        interpolateMap(mutated, context, "");
        return mutated;
    }

    @SuppressWarnings("unchecked")
    private void interpolateMap(Map<String, Object> map, SeedContext context, String path) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            String fieldPath = path.isEmpty() ? key : path + "." + key;
            Object value = entry.getValue();

            if (value instanceof String strValue) {
                if (shouldProcess(key, fieldPath) && containsVariable(strValue)) {
                    String interpolated = interpolateString(strValue, context, fieldPath);
                    entry.setValue(interpolated);
                }
            } else if (value instanceof Map) {
                interpolateMap((Map<String, Object>) value, context, fieldPath);
            } else if (value instanceof List) {
                interpolateList((List<Object>) value, context, fieldPath);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void interpolateList(List<Object> list, SeedContext context, String path) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String itemPath = path + "[" + i + "]";

            if (item instanceof String strValue) {
                if (containsVariable(strValue)) {
                    String interpolated = interpolateString(strValue, context, itemPath);
                    list.set(i, interpolated);
                }
            } else if (item instanceof Map) {
                interpolateMap((Map<String, Object>) item, context, itemPath);
            } else if (item instanceof List) {
                interpolateList((List<Object>) item, context, itemPath);
            }
        }
    }

    private boolean shouldProcess(String fieldName, String fieldPath) {
        if (targetFields == null || targetFields.isEmpty()) {
            return true;
        }
        // Check both simple field name and full path
        return targetFields.contains(fieldName) || targetFields.contains(fieldPath);
    }

    private boolean containsVariable(String value) {
        return value != null && value.contains("{") && value.contains("}");
    }

    private String interpolateString(String value, SeedContext context, String fieldPath) {
        Matcher matcher = VARIABLE_PATTERN.matcher(value);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            Optional<String> resolved = resolveVariable(variableName, context);

            if (resolved.isPresent()) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(resolved.get()));
            } else if (failOnMissing) {
                throw new SeedLoadingException(
                        String.format("Failed to resolve variable '{%s}' in field '%s'", variableName, fieldPath));
            } else {
                // Leave unresolved variable as-is
                Log.debugf("Variable '{%s}' in field '%s' could not be resolved, leaving as-is",
                        variableName, fieldPath);
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private Optional<String> resolveVariable(String variableName, SeedContext context) {
        for (SeedVariableResolver resolver : resolvers) {
            Optional<String> resolved = resolver.resolve(variableName, context);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    /**
     * Creates a StringInterpolationTransform from a transform definition.
     */
    @SuppressWarnings("unchecked")
    public static StringInterpolationTransform from(Transform transform, List<SeedVariableResolver> resolvers) {
        Map<String, Object> config = transform.getConfig();

        Set<String> targetFields = null;
        Object fieldsObj = config.get("fields");
        if (fieldsObj instanceof List<?> fieldsList) {
            targetFields = new LinkedHashSet<>();
            for (Object f : fieldsList) {
                if (f != null) {
                    targetFields.add(f.toString());
                }
            }
        }

        boolean failOnMissing = Boolean.parseBoolean(
                String.valueOf(config.getOrDefault("failOnMissing", Boolean.FALSE)));

        return new StringInterpolationTransform(targetFields, failOnMissing, resolvers);
    }

    /**
     * Factory for creating StringInterpolationTransform instances.
     *
     * <p>The factory accepts a list of SeedVariableResolver instances that will be used
     * for variable resolution. The built-in SeedContextVariableResolver is always included.</p>
     */
    public static final class Factory implements SeedTransformFactory {

        private final List<SeedVariableResolver> resolvers;

        public Factory() {
            this(List.of());
        }

        public Factory(List<SeedVariableResolver> customResolvers) {
            List<SeedVariableResolver> all = new ArrayList<>();
            // Add custom resolvers first
            if (customResolvers != null) {
                all.addAll(customResolvers);
            }
            // Always include the built-in context resolver
            all.add(SeedContextVariableResolver.INSTANCE);
            this.resolvers = List.copyOf(all);
        }

        @Override
        public SeedTransform create(Transform transformDefinition) {
            return StringInterpolationTransform.from(transformDefinition, resolvers);
        }
    }
}

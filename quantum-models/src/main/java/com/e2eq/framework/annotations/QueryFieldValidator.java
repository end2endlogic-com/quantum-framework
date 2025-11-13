package com.e2eq.framework.annotations;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Property;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Validates field references in query strings against model classes or aggregation schemas.
 * Detects references to non-existent attributes before query execution.
 */
public class QueryFieldValidator {
    private final Set<String> validFields;
    private final Map<String, Class<?>> fieldTypes;
    private final List<String> errors = new ArrayList<>();

    private QueryFieldValidator(Set<String> validFields, Map<String, Class<?>> fieldTypes) {
        this.validFields = validFields;
        this.fieldTypes = fieldTypes;
    }

    /**
     * Creates a validator for a model class by introspecting its fields.
     */
    public static QueryFieldValidator forModelClass(Class<? extends UnversionedBaseModel> modelClass) {
        Set<String> fields = new HashSet<>();
        Map<String, Class<?>> types = new HashMap<>();
        collectFields(modelClass, "", fields, types);
        return new QueryFieldValidator(fields, types);
    }

    /**
     * Creates a validator for an aggregation pipeline result with explicit field names.
     */
    public static QueryFieldValidator forAggregationSchema(Map<String, Class<?>> schema) {
        Set<String> fields = new HashSet<>(schema.keySet());
        return new QueryFieldValidator(fields, new HashMap<>(schema));
    }

    /**
     * Creates a validator with explicit field list (no type checking).
     */
    public static QueryFieldValidator forFields(Set<String> fields) {
        return new QueryFieldValidator(new HashSet<>(fields), Collections.emptyMap());
    }

    /**
     * Validates a field path. Returns true if valid, false otherwise.
     * Errors are accumulated and can be retrieved via getErrors().
     */
    public boolean validateField(String fieldPath) {
        if (fieldPath == null || fieldPath.isEmpty()) {
            errors.add("Empty field path");
            return false;
        }

        if (validFields.contains(fieldPath)) {
            return true;
        }

        String[] parts = fieldPath.split("\\.");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) prefix.append(".");
            prefix.append(parts[i]);
            if (validFields.contains(prefix.toString())) {
                return true;
            }
        }

        errors.add("Field not found: " + fieldPath);
        return false;
    }

    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }

    public void clearErrors() {
        errors.clear();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public Optional<Class<?>> getFieldType(String fieldPath) {
        return Optional.ofNullable(fieldTypes.get(fieldPath));
    }

    private static void collectFields(Class<?> clazz, String prefix, Set<String> fields, Map<String, Class<?>> types) {
        if (clazz == null || clazz == Object.class) return;

        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            String fieldName = getFieldName(field);
            String fullPath = prefix.isEmpty() ? fieldName : prefix + "." + fieldName;
            
            fields.add(fullPath);
            types.put(fullPath, field.getType());

            if (!isPrimitiveOrWrapper(field.getType()) && 
                !field.getType().getName().startsWith("java.lang") &&
                !field.getType().getName().startsWith("java.util") &&
                !field.getType().getName().startsWith("java.time") &&
                !field.getType().isEnum()) {
                collectFields(field.getType(), fullPath, fields, types);
            }
        }

        collectFields(clazz.getSuperclass(), prefix, fields, types);
    }

    private static String getFieldName(Field field) {
        Property prop = field.getAnnotation(Property.class);
        if (prop != null && !prop.value().isEmpty()) {
            return prop.value();
        }
        return field.getName();
    }

    private static boolean isPrimitiveOrWrapper(Class<?> type) {
        return type.isPrimitive() || 
               type == Boolean.class || type == Integer.class || type == Long.class ||
               type == Float.class || type == Double.class || type == Character.class ||
               type == Byte.class || type == Short.class || type == String.class;
    }
}

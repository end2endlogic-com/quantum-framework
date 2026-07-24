package com.e2eq.framework.service.seed;

import dev.morphia.annotations.Entity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SeedCollectionResolver {

    private SeedCollectionResolver() {
    }

    public static String deriveCollectionName(String modelClassName) {
        if (modelClassName == null || modelClassName.isBlank()) {
            return null;
        }
        try {
            Class<?> modelClass = Class.forName(modelClassName, true, Thread.currentThread().getContextClassLoader());
            Entity ann = modelClass.getAnnotation(Entity.class);
            if (ann != null && ann.value() != null && !ann.value().isBlank() && !".".equals(ann.value())) {
                return ann.value();
            }
            return decapitalize(modelClass.getSimpleName());
        } catch (ClassNotFoundException e) {
            int lastDot = modelClassName.lastIndexOf('.');
            String simple = lastDot >= 0 ? modelClassName.substring(lastDot + 1) : modelClassName;
            return decapitalize(simple);
        }
    }

    public static void ensureCollectionSet(SeedPackManifest.Dataset dataset) {
        if (dataset.getCollection() != null && !dataset.getCollection().isBlank() && !".".equals(dataset.getCollection())) {
            return;
        }
        String derived = deriveCollectionName(dataset.getModelClass());
        if (derived != null && !derived.isBlank()) {
            dataset.setCollection(derived);
        }
    }

    public static List<String> lookupCollectionNames(SeedPackManifest.Dataset dataset) {
        Set<String> names = new LinkedHashSet<>();
        String current = dataset.getCollection();
        if (current != null && !current.isBlank()) {
            names.add(current);
        }

        String modelClassName = dataset.getModelClass();
        String derived = deriveCollectionName(modelClassName);
        if (derived != null && !derived.isBlank()) {
            names.add(derived);
        }

        String legacy = deriveEntityAnnotationValue(modelClassName);
        if (legacy != null && !legacy.isBlank()) {
            names.add(legacy);
        }
        return new ArrayList<>(names);
    }

    private static String deriveEntityAnnotationValue(String modelClassName) {
        if (modelClassName == null || modelClassName.isBlank()) {
            return null;
        }
        try {
            Class<?> modelClass = Class.forName(modelClassName, true, Thread.currentThread().getContextClassLoader());
            Entity ann = modelClass.getAnnotation(Entity.class);
            return ann != null ? ann.value() : null;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}

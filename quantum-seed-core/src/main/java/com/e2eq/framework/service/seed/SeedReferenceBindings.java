package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Reference;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SeedReferenceBindings {

    private SeedReferenceBindings() {
    }

    static List<SeedPackManifest.ReferenceBinding> resolveBindings(SeedPackManifest.Dataset dataset) {
        Map<String, SeedPackManifest.ReferenceBinding> merged = new LinkedHashMap<>();

        for (SeedPackManifest.ReferenceBinding binding : dataset.getReferences()) {
            merged.put(binding.getField(), binding);
        }

        Class<?> modelClass = loadModelClass(dataset);
        if (modelClass == null) {
            return new ArrayList<>(merged.values());
        }

        for (Field field : getAllFields(modelClass)) {
            SeedPackManifest.ReferenceBinding inferred = inferBinding(field);
            if (inferred != null) {
                merged.putIfAbsent(inferred.getField(), inferred);
            }
        }

        return new ArrayList<>(merged.values());
    }

    private static SeedPackManifest.ReferenceBinding inferBinding(Field field) {
        if (field.getAnnotation(Reference.class) != null) {
            return inferMorphiaReferenceBinding(field);
        }
        if (EntityReference.class.isAssignableFrom(field.getType()) || isEntityReferenceCollection(field)) {
            ReferenceTarget referenceTarget = field.getAnnotation(ReferenceTarget.class);
            if (referenceTarget != null) {
                SeedPackManifest.ReferenceBinding binding = new SeedPackManifest.ReferenceBinding();
                binding.setField(field.getName());
                binding.setTargetModelClass(referenceTarget.target().getName());
                binding.setTargetCollection(referenceTarget.collection());
                binding.setMany(isCollection(field));
                return binding;
            }
        }
        return null;
    }

    private static SeedPackManifest.ReferenceBinding inferMorphiaReferenceBinding(Field field) {
        SeedPackManifest.ReferenceBinding binding = new SeedPackManifest.ReferenceBinding();
        binding.setField(field.getName());
        binding.setMany(isCollection(field));

        Class<?> targetType = isCollection(field) ? resolveCollectionElementType(field) : field.getType();
        if (targetType != null && UnversionedBaseModel.class.isAssignableFrom(targetType)) {
            binding.setTargetModelClass(targetType.getName());
        }
        return binding.getTargetModelClass() == null ? null : binding;
    }

    private static Class<?> loadModelClass(SeedPackManifest.Dataset dataset) {
        String modelClassName = dataset.getModelClass();
        if (modelClassName == null || modelClassName.isBlank()) {
            return null;
        }
        try {
            return Class.forName(modelClassName, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SeedLoadingException("Unable to load modelClass " + modelClassName + " while resolving seed reference bindings", e);
        }
    }

    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static boolean isCollection(Field field) {
        return java.util.Collection.class.isAssignableFrom(field.getType());
    }

    private static boolean isEntityReferenceCollection(Field field) {
        Class<?> elementType = resolveCollectionElementType(field);
        return isCollection(field) && elementType != null && EntityReference.class.isAssignableFrom(elementType);
    }

    private static Class<?> resolveCollectionElementType(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] arguments = parameterizedType.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        Type candidate = arguments[0];
        if (candidate instanceof Class<?> clazz) {
            return clazz;
        }
        if (candidate instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }
}

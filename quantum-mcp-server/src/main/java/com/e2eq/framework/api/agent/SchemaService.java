package com.e2eq.framework.api.agent;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway-derived schema for agent use: list root types and JSON Schema for a single root type.
 * Uses the same Morphia mapping as the Query Gateway.
 */
@ApplicationScoped
public class SchemaService {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "defaultRealm")
    String defaultRealm;

    /**
     * Resolves rootType (simple name or FQCN) to entity class. Same logic as QueryGatewayResource.resolveRoot.
     */
    @SuppressWarnings("unchecked")
    public Class<? extends UnversionedBaseModel> resolveRoot(String rootType) {
        if (rootType == null || rootType.isBlank()) {
            throw new BadRequestException("rootType is required");
        }
        if (rootType.contains(".")) {
            try {
                Class<?> c = Class.forName(rootType);
                if (!UnversionedBaseModel.class.isAssignableFrom(c)) {
                    throw new BadRequestException("rootType must extend UnversionedBaseModel: " + rootType);
                }
                return (Class<? extends UnversionedBaseModel>) c;
            } catch (ClassNotFoundException e) {
                throw new BadRequestException("Unknown rootType: " + rootType);
            }
        }
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(defaultRealm);
        Mapper mapper = ds.getMapper();
        List<Class<?>> matches = new ArrayList<>();
        for (EntityModel em : mapper.getMappedEntities()) {
            Class<?> type = em.getType();
            if (UnversionedBaseModel.class.isAssignableFrom(type) && type.getSimpleName().equals(rootType)) {
                matches.add(type);
            }
        }
        if (matches.isEmpty()) {
            throw new BadRequestException("Unknown rootType: '" + rootType + "'. Use GET /api/query/rootTypes to see available types.");
        }
        if (matches.size() > 1) {
            List<String> fqns = matches.stream().map(Class::getName).sorted().toList();
            throw new BadRequestException("Ambiguous rootType: '" + rootType + "' matches multiple classes: " + fqns + ". Please use the fully qualified class name.");
        }
        return (Class<? extends UnversionedBaseModel>) matches.get(0);
    }

    /**
     * Returns JSON Schema-like structure for the given root type (properties from entity class fields).
     */
    public Map<String, Object> getSchemaForRootType(String rootType) {
        Class<? extends UnversionedBaseModel> root = resolveRoot(rootType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("title", root.getSimpleName());
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Field f : getDeclaredFieldsIncludingSuper(root)) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String name = f.getName();
            if ("_id".equals(name) || "id".equals(name) && org.bson.types.ObjectId.class.equals(f.getType())) continue;
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", javaTypeToJsonSchemaType(f.getType()));
            properties.put(name, prop);
        }
        schema.put("properties", properties);
        return schema;
    }

    private static List<Field> getDeclaredFieldsIncludingSuper(Class<?> type) {
        List<Field> list = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                list.add(f);
            }
            current = current.getSuperclass();
        }
        return list;
    }

    private static String javaTypeToJsonSchemaType(Class<?> type) {
        if (type == null) return "string";
        if (type == String.class || type == Character.class || type == char.class) return "string";
        if (type == Integer.class || type == int.class || type == Long.class || type == long.class
                || type == Short.class || type == short.class || type == Byte.class || type == byte.class) return "integer";
        if (type == Double.class || type == double.class || type == Float.class || type == float.class) return "number";
        if (type == Boolean.class || type == boolean.class) return "boolean";
        if (type.isArray() || List.class.isAssignableFrom(type)) return "array";
        if (java.util.Date.class.isAssignableFrom(type) || java.time.temporal.Temporal.class.isAssignableFrom(type)) return "string"; // format date-time
        if (org.bson.types.ObjectId.class.equals(type)) return "string";
        return "object";
    }
}

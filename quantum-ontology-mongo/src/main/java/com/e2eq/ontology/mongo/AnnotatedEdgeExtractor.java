package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.core.Reasoner;
import dev.morphia.MorphiaDatastore;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Extracts explicit ontology edges from annotated entity instances using
 * precomputed metadata for performance. Intended to be used on the write path.
 */
@ApplicationScoped
public class AnnotatedEdgeExtractor {

    public static final class PropertyBinding {
        public final String predicateId;
        public final MethodHandle accessor;
        public final boolean collection;
        public final boolean materialize;
        public final String rangeType;
        PropertyBinding(String predicateId,
                        MethodHandle accessor,
                        boolean collection,
                        boolean materialize,
                        String rangeType) {
            this.predicateId = predicateId;
            this.accessor = accessor;
            this.collection = collection;
            this.materialize = materialize;
            this.rangeType = rangeType;
        }
    }
    public static final class ClassMeta {
        public final String classId;
        public final List<PropertyBinding> properties;
        ClassMeta(String classId, List<PropertyBinding> properties) {
            this.classId = classId;
            this.properties = properties;
        }
    }

    private final Map<Class<?>, ClassMeta> metas = new HashMap<>();

    @Inject
    MorphiaDatastore datastore;

    @PostConstruct
    void init() {
        // Discover Morphia entity classes
        var loader = new MorphiaOntologyLoader(datastore);
        Collection<Class<?>> entityClasses = discoverEntityClasses(loader);
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Class<?> clazz : entityClasses) {
            OntologyClass oc = clazz.getAnnotation(OntologyClass.class);
            // Only track classes explicitly participating in ontology
            if (oc == null) continue;
            String classId = classIdOf(clazz);
            List<PropertyBinding> props = new ArrayList<>();
            for (Field f : clazz.getDeclaredFields()) {
                OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
                if (pa == null) continue;
                String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? f.getName() : pa.id());
                try {
                    f.setAccessible(true);
                    MethodHandle mh = lookup.unreflectGetter(f);
                    boolean isCol = Collection.class.isAssignableFrom(f.getType()) || f.getType().isArray();
                    boolean materialize = pa.materializeEdge();
                    String rangeType = resolveRangeType(pa, f.getType(), f.getGenericType());
                    props.add(new PropertyBinding(pid, mh, isCol, materialize, rangeType));
                } catch (IllegalAccessException ignored) {}
            }
            for (Method m : clazz.getDeclaredMethods()) {
                OntologyProperty pa = m.getAnnotation(OntologyProperty.class);
                if (pa == null) continue;
                String name = m.getName();
                String derived = (name.startsWith("get") && name.length() > 3) ? Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
                String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? derived : pa.id());
                try {
                    m.setAccessible(true);
                    MethodHandle mh = lookup.unreflect(m);
                    boolean isCol = Collection.class.isAssignableFrom(m.getReturnType()) || m.getReturnType().isArray();
                    boolean materialize = pa.materializeEdge();
                    String rangeType = resolveRangeType(pa, m.getReturnType(), m.getGenericReturnType());
                    props.add(new PropertyBinding(pid, mh, isCol, materialize, rangeType));
                } catch (IllegalAccessException ignored) {}
            }
            metas.put(clazz, new ClassMeta(classId, List.copyOf(props)));
        }
    }

    private Collection<Class<?>> discoverEntityClasses(MorphiaOntologyLoader loader) {
        try {
            java.lang.reflect.Method m = MorphiaOntologyLoader.class.getDeclaredMethod("discoverEntityClasses");
            m.setAccessible(true);
            Object o = m.invoke(loader);
            @SuppressWarnings("unchecked")
            Collection<Class<?>> c = (Collection<Class<?>>) o;
            return c;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private String classIdOf(Class<?> clazz) {
        var a = clazz.getAnnotation(OntologyClass.class);
        if (a != null && !a.id().isEmpty()) return a.id();
        return clazz.getSimpleName();
    }

    @Inject
    DefaultIdAccessor idAccessor;

    public String idOf(Object entity) {
        DefaultIdAccessor acc = (this.idAccessor != null) ? this.idAccessor : new DefaultIdAccessor();
        return acc.idOf(entity);
    }

    public Optional<ClassMeta> metaOf(Class<?> clazz) {
        ClassMeta meta = metas.get(clazz);
        if (meta != null) return Optional.of(meta);
        // Fallback: build metadata on demand for classes not discovered at startup
        ClassMeta built = buildMetaFor(clazz);
        if (built != null) {
            metas.put(clazz, built);
            return Optional.of(built);
        }
        return Optional.empty();
    }

    private ClassMeta buildMetaFor(Class<?> clazz) {
        OntologyClass oc = clazz.getAnnotation(OntologyClass.class);
        if (oc == null) return null;
        String classId = classIdOf(clazz);
        List<PropertyBinding> props = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Field f : clazz.getDeclaredFields()) {
            OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
            if (pa == null) continue;
            String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? f.getName() : pa.id());
            try {
                f.setAccessible(true);
                MethodHandle mh = lookup.unreflectGetter(f);
                boolean isCol = Collection.class.isAssignableFrom(f.getType()) || f.getType().isArray();
                boolean materialize = pa.materializeEdge();
                String rangeType = resolveRangeType(pa, f.getType(), f.getGenericType());
                props.add(new PropertyBinding(pid, mh, isCol, materialize, rangeType));
            } catch (IllegalAccessException ignored) {}
        }
        for (Method m : clazz.getDeclaredMethods()) {
            OntologyProperty pa = m.getAnnotation(OntologyProperty.class);
            if (pa == null) continue;
            String name = m.getName();
            String derived = (name.startsWith("get") && name.length() > 3) ? Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
            String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? derived : pa.id());
            try {
                m.setAccessible(true);
                MethodHandle mh = lookup.unreflect(m);
                boolean isCol = Collection.class.isAssignableFrom(m.getReturnType()) || m.getReturnType().isArray();
                boolean materialize = pa.materializeEdge();
                String rangeType = resolveRangeType(pa, m.getReturnType(), m.getGenericReturnType());
                props.add(new PropertyBinding(pid, mh, isCol, materialize, rangeType));
            } catch (IllegalAccessException ignored) {}
        }
        return new ClassMeta(classId, List.copyOf(props));
    }

    public List<Reasoner.Edge> fromEntity(String tenantId, Object entity) {
        var meta = metaOf(entity.getClass());
        if (meta.isEmpty()) return List.of();
        String srcId = idAccessor.idOf(entity);
        List<Reasoner.Edge> out = new ArrayList<>();
        for (PropertyBinding b : meta.get().properties) {
            if (!b.materialize) {
                continue; // skip edge extraction when materialization disabled
            }
            Object val;
            try {
                val = b.accessor.invoke(entity);
            } catch (Throwable t) {
                continue;
            }
            addEdges(out, meta.get().classId, b, srcId, val);
        }
        return out;
    }

    private void addEdges(List<Reasoner.Edge> out, String srcType, PropertyBinding binding, String srcId, Object val) {
        if (val == null) return;
        if (val instanceof Collection<?> c) {
            for (Object v : c) addSingle(out, srcType, binding, srcId, v);
        } else if (val.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(val);
            for (int i = 0; i < len; i++) addSingle(out, srcType, binding, srcId, java.lang.reflect.Array.get(val, i));
        } else {
            addSingle(out, srcType, binding, srcId, val);
        }
    }

    private void addSingle(List<Reasoner.Edge> out, String srcType, PropertyBinding binding, String srcId, Object target) {
        if (target == null) return;
        String dstId = idAccessor.idOf(target);
        String dstType = resolveTargetType(binding.rangeType, target);
        if (dstType == null) {
            throw new IllegalStateException("Unable to determine destination type for predicate " + binding.predicateId);
        }
        out.add(new Reasoner.Edge(srcId, srcType, binding.predicateId, dstId, dstType, false, Optional.empty()));
    }

    private String resolveTargetType(String declaredRange, Object target) {
        if (declaredRange != null && !declaredRange.isBlank()) {
            return declaredRange;
        }
        if (target != null) {
            if (target instanceof Class<?> clazz) {
                return classIdOf(clazz);
            }
            return classIdOf(target.getClass());
        }
        return null;
    }

    private String resolveRangeType(OntologyProperty property, Class<?> rawType, java.lang.reflect.Type genericType) {
        if (property != null) {
            if (!property.ref().isEmpty()) return property.ref();
            if (!property.range().isEmpty()) return property.range();
        }
        Class<?> candidate = rawType;
        if (Collection.class.isAssignableFrom(rawType)) {
            if (genericType instanceof java.lang.reflect.ParameterizedType p) {
                java.lang.reflect.Type t = p.getActualTypeArguments().length > 0 ? p.getActualTypeArguments()[0] : Object.class;
                if (t instanceof Class<?> c) {
                    candidate = c;
                }
            }
        } else if (rawType.isArray()) {
            candidate = rawType.getComponentType();
        }
        if (candidate != null && candidate != Object.class) {
            return classIdOf(candidate);
        }
        return null;
    }

    @ApplicationScoped
    public static class DefaultIdAccessor {
        public String idOf(Object entity) {
            if (entity == null) return null;
            // If the target is already a CharSequence (e.g., String id), use it directly
            if (entity instanceof CharSequence cs) return cs.toString();
            if (entity instanceof EntityReference ref) {
                if (ref.getEntityId() != null) {
                    return ref.getEntityId().toHexString();
                }
                if (ref.getEntityRefName() != null) {
                    return ref.getEntityRefName();
                }
            }
            try {
                Method m = entity.getClass().getMethod("getRefName");
                Object v = m.invoke(entity);
                if (v != null) return String.valueOf(v);
            } catch (Exception ignored) {}
            try {
                Method m = entity.getClass().getMethod("getId");
                Object v = m.invoke(entity);
                if (v != null) return String.valueOf(v);
            } catch (Exception ignored) {}
            return String.valueOf(entity.hashCode());
        }
    }
}

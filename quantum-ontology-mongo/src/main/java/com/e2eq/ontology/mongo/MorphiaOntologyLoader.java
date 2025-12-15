package com.e2eq.ontology.mongo;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.annotations.RelationType;
import com.e2eq.ontology.core.InMemoryOntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry;
import dev.morphia.MorphiaDatastore;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Builds an OntologyRegistry (TBox) by inspecting Morphia's mapper and model annotations.
 * This favors a model-first approach with minimal configuration.
 */
public final class MorphiaOntologyLoader {

    private final MorphiaDatastore datastore;

    public MorphiaOntologyLoader(MorphiaDatastore datastore) {
        this.datastore = datastore;
    }

    public OntologyRegistry load() {
        OntologyRegistry.TBox tbox = loadTBox();
        return new InMemoryOntologyRegistry(tbox);
    }

    public OntologyRegistry.TBox loadTBox() {
        Map<String, OntologyRegistry.ClassDef> classes = new HashMap<>();
        Map<String, OntologyRegistry.PropertyDef> props = new HashMap<>();
        List<OntologyRegistry.PropertyChainDef> chains = new ArrayList<>(); // chains not derived here yet

        // Discover mapped entity classes from Morphia Mapper
        Collection<Class<?>> entityClasses = discoverEntityClasses();
        // First pass: classes
        for (Class<?> clazz : entityClasses) {
            String classId = classIdOf(clazz);
            Set<String> parents = new LinkedHashSet<>();
            // Java inheritance chain among mapped entities
            Class<?> p = clazz.getSuperclass();
            while (p != null && p != Object.class) {
                if (entityClasses.contains(p)) {
                    parents.add(classIdOf(p));
                }
                p = p.getSuperclass();
            }
            OntologyClass anno = clazz.getAnnotation(OntologyClass.class);
            if (anno != null) {
                parents.addAll(Arrays.asList(anno.subClassOf()));
            }
            classes.put(classId, new OntologyRegistry.ClassDef(classId, parents, Set.of(), Set.of()));
        }

        // Second pass: properties from fields
        for (Class<?> clazz : entityClasses) {
            String domain = classIdOf(clazz);
            for (Field f : clazz.getDeclaredFields()) {
                OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
                // Consider only annotated fields for now to avoid over-eager edge creation
                if (pa == null) continue;
                String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? f.getName() : pa.id());
                String range = null;
                if (!pa.ref().isEmpty()) {
                    range = pa.ref();
                } else if (!pa.range().isEmpty()) {
                    range = pa.range();
                } else {
                    // Try to infer from @ReferenceTarget on the field first
                    range = inferRangeFromReferenceTarget(f, entityClasses).orElseGet(
                            () -> inferRangeFromField(f, entityClasses).orElse(null)
                    );
                }
                Optional<String> rangeOpt = Optional.ofNullable(range);
                Optional<String> domainOpt = Optional.of(domain);
                boolean functional;
                if (pa.relation() != RelationType.NONE) {
                    functional = (pa.relation() == RelationType.ONE_TO_ONE || pa.relation() == RelationType.MANY_TO_ONE);
                } else {
                    functional = pa.functional();
                    if (!functional) {
                        // infer functional from single-valued (non-collection) field
                        functional = !isCollectionOrArray(f.getType());
                    }
                }
                Set<String> superProps = new LinkedHashSet<>(Arrays.asList(pa.subPropertyOf()));
                Optional<String> inverseOf = pa.inverseOf().isEmpty() ? Optional.empty() : Optional.of(pa.inverseOf());
                OntologyRegistry.PropertyDef def = new OntologyRegistry.PropertyDef(
                        pid, domainOpt, rangeOpt, inverseOf.isPresent(), inverseOf, pa.transitive(), pa.symmetric(), functional, superProps
                );
                props.put(pid, def);
            }
        }

        // Third pass: ensure inverse pairing exists in registry (add placeholder if necessary)
        for (OntologyRegistry.PropertyDef def : new ArrayList<>(props.values())) {
            def.inverseOf().ifPresent(invName -> {
                if (!props.containsKey(invName)) {
                    // Create a minimal inverse property definition if counterpart not annotated
                    Optional<String> invDomain = def.range();
                    Optional<String> invRange = def.domain();
                    OntologyRegistry.PropertyDef invDef = new OntologyRegistry.PropertyDef(
                            invName, invDomain, invRange, true, Optional.empty(), false, false, false, Set.of()
                    );
                    props.put(invName, invDef);
                }
            });
        }

        return new OntologyRegistry.TBox(classes, props, chains);
    }

    private String classIdOf(Class<?> clazz) {
        OntologyClass a = clazz.getAnnotation(OntologyClass.class);
        if (a != null && !a.id().isEmpty()) return a.id();
        return clazz.getSimpleName();
    }

    private boolean isCollectionOrArray(Class<?> t) {
        return t.isArray() || Collection.class.isAssignableFrom(t);
    }

    private Optional<String> inferRangeFromField(Field f, Collection<Class<?>> mapped) {
        Class<?> type = f.getType();
        if (isCollectionOrArray(type)) {
            // handle generics like List<Foo>
            Type gen = f.getGenericType();
            if (gen instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length == 1 && args[0] instanceof Class<?> c && mapped.contains(c)) {
                    return Optional.of(classIdOf(c));
                }
            }
            return Optional.empty();
        }
        if (mapped.contains(type)) {
            return Optional.of(classIdOf(type));
        }
        return Optional.empty();
    }

    private Optional<String> inferRangeFromReferenceTarget(Field field, Collection<Class<?>> mapped) {
        if (field == null) return Optional.empty();
        try {
            Class<?> rtAnno = Class.forName("com.e2eq.framework.model.persistent.base.ReferenceTarget");
            if (!field.isAnnotationPresent((Class) rtAnno)) return Optional.empty();
            var ann = field.getAnnotation((Class) rtAnno);
            var targetMethod = rtAnno.getMethod("target");
            Object targetClassObj = targetMethod.invoke(ann);
            if (targetClassObj instanceof Class<?> targetClass) {
                String id = mapped.contains(targetClass) ? classIdOf(targetClass) : targetClass.getSimpleName();
                return Optional.ofNullable(id);
            }
        } catch (Throwable ignored) { }
        return Optional.empty();
    }

    private Collection<Class<?>> discoverEntityClasses() {
        try {
            var mapper = datastore.getMapper();
            // Use getMappedEntities() which returns List<EntityModel>
            java.util.List<dev.morphia.mapping.codec.pojo.EntityModel> entityModels = mapper.getMappedEntities();
            Collection<Class<?>> classes = new LinkedHashSet<>();
            for (dev.morphia.mapping.codec.pojo.EntityModel model : entityModels) {
                try {
                    Class<?> entityClass = model.getType();
                    if (entityClass != null) {
                        classes.add(entityClass);
                    }
                } catch (Exception e) {
                    // If getType() fails, try alternative approach
                    try {
                        // Some Morphia versions may have getEntityClass() method
                        java.lang.reflect.Method getEntityClass = model.getClass().getMethod("getEntityClass");
                        Object c = getEntityClass.invoke(model);
                        if (c instanceof Class<?>) {
                            classes.add((Class<?>) c);
                        }
                    } catch (NoSuchMethodException ignored) {
                        // Skip this model if we can't determine its type
                    }
                }
            }
            return classes;
        } catch (Exception e) {
            return List.of();
        }
    }
}

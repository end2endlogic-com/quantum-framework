package com.e2eq.ontology.mongo;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
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
                String pid = pa.id().isEmpty() ? f.getName() : pa.id();
                String range = inferRangeFromField(f, entityClasses).orElse(pa.range().isEmpty() ? null : pa.range());
                Optional<String> rangeOpt = Optional.ofNullable(range);
                Optional<String> domainOpt = Optional.of(domain);
                boolean functional = pa.functional();
                if (!functional) {
                    // infer functional from single-valued (non-collection) field
                    functional = !isCollectionOrArray(f.getType());
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

    @SuppressWarnings("unchecked")
    private Collection<Class<?>> discoverEntityClasses() {
        try {
            var mapper = datastore.getMapper();
            // Morphia 2.x: mapper.getEntityModels() returns a collection of EntityModel with getType()
            var method = mapper.getClass().getMethod("getEntityModels");
            Object models = method.invoke(mapper);
            Collection<Class<?>> classes = new LinkedHashSet<>();
            for (Object m : (Collection<?>) models) {
                try {
                    var getType = m.getClass().getMethod("getType");
                    Object c = getType.invoke(m);
                    if (c instanceof Class<?>) classes.add((Class<?>) c);
                } catch (NoSuchMethodException ignored) {
                    // Older Morphia: try getType() on model; if not, try getEntityClass()
                    try {
                        var getEntityClass = m.getClass().getMethod("getEntityClass");
                        Object c = getEntityClass.invoke(m);
                        if (c instanceof Class<?>) classes.add((Class<?>) c);
                    } catch (NoSuchMethodException ignored2) { /* give up on this model */ }
                }
            }
            return classes;
        } catch (Exception e) {
            return List.of();
        }
    }
}

package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.annotations.RelationType;
import io.quarkus.logging.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads an OntologyRegistry.TBox by scanning configured packages for classes annotated with
 * {@link OntologyClass} and fields/methods annotated with {@link OntologyProperty}.
 *
 * This uses simple classpath resource scanning that works for exploded directories and JARs.
 * It is intentionally lightweight to avoid bringing heavy reflection libraries.
 */
public final class AnnotationOntologyLoader {

    public OntologyRegistry.TBox loadFromPackages(Collection<String> packageNames) {
        Set<Class<?>> annotatedClasses = new LinkedHashSet<>();
        for (String pkg : packageNames) {
            annotatedClasses.addAll(findClassesWithAnnotation(pkg.trim(), OntologyClass.class));
        }

        Map<String, OntologyRegistry.ClassDef> classes = new LinkedHashMap<>();
        Map<String, OntologyRegistry.PropertyDef> props = new LinkedHashMap<>();
        List<OntologyRegistry.PropertyChainDef> chains = new ArrayList<>(); // not derived here

        // Build class defs
        for (Class<?> c : annotatedClasses) {

            String id = classIdOf(c);
            Log.infof("AnnotationOntologyLoader loaded id:%s",id);
            Set<String> parents = new LinkedHashSet<>();
            OntologyClass oc = c.getAnnotation(OntologyClass.class);
            if (oc != null){
               parents.addAll(Arrays.asList(oc.subClassOf()));
            }
            // Also consider Java super classes if they are annotated too
            Class<?> p = c.getSuperclass();
            while (p != null && p != Object.class) {
                if (annotatedClasses.contains(p)) parents.add(classIdOf(p));
                p = p.getSuperclass();
            }
            classes.put(id, new OntologyRegistry.ClassDef(id, parents, Set.of(), Set.of()));
        }

        // Build property defs from fields and methods on annotated classes
        for (Class<?> c : annotatedClasses) {
            String domain = classIdOf(c);
            // Fields
            for (Field f : c.getDeclaredFields()) {
                OntologyProperty pa = f.getAnnotation(OntologyProperty.class);
                if (pa == null) continue;
                addPropertyDefFromMember(props, pa, domain, f.getName(), f.getType(), f.getGenericType(), annotatedClasses, f);
            }
            // Methods: we only read the annotation meta; not inferring return type mapping beyond simple classes
            Arrays.stream(c.getDeclaredMethods()).forEach(m -> {
                OntologyProperty pa = m.getAnnotation(OntologyProperty.class);
                if (pa == null) return;
                String name = m.getName();
                Class<?> raw = m.getReturnType();
                Type gen = m.getGenericReturnType();
                addPropertyDefFromMember(props, pa, domain, derivedNameFromMethod(name), raw, gen, annotatedClasses);
            });
        }

        // Ensure inverse placeholders exist
        for (OntologyRegistry.PropertyDef def : new ArrayList<>(props.values())) {
            def.inverseOf().ifPresent(inv -> {
                if (!props.containsKey(inv)) {
                    Optional<String> invDomain = def.range();
                    Optional<String> invRange = def.domain();
                    props.put(inv, new OntologyRegistry.PropertyDef(inv, invDomain, invRange, true, Optional.empty(), false, false, false, Set.of()));
                }
            });
        }

        return new OntologyRegistry.TBox(classes, props, chains);
    }

    private void addPropertyDefFromMember(Map<String, OntologyRegistry.PropertyDef> props,
                                          OntologyProperty pa,
                                          String domain,
                                          String memberName,
                                          Class<?> rawType,
                                          Type genericType,
                                          Collection<Class<?>> annotatedClasses) {
        // Delegate to the Field-aware overload with null field
        addPropertyDefFromMember(props, pa, domain, memberName, rawType, genericType, annotatedClasses, null);
    }

    private void addPropertyDefFromMember(Map<String, OntologyRegistry.PropertyDef> props,
                                          OntologyProperty pa,
                                          String domain,
                                          String memberName,
                                          Class<?> rawType,
                                          Type genericType,
                                          Collection<Class<?>> annotatedClasses,
                                          Field field) {
        String pid = !pa.edgeType().isEmpty() ? pa.edgeType() : (pa.id().isEmpty() ? memberName : pa.id());
        String range = null;
        if (!pa.ref().isEmpty()) {
            range = pa.ref();
        } else if (!pa.range().isEmpty()) {
            range = pa.range();
        } else {
            // Try to infer from @ReferenceTarget when present (on fields only)
            range = inferRangeFromReferenceTarget(field, annotatedClasses).orElseGet(
                    () -> inferRange(rawType, genericType, annotatedClasses).orElse(null)
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
                functional = !isCollectionOrArray(rawType);
            }
        }
        Set<String> superProps = new LinkedHashSet<>(Arrays.asList(pa.subPropertyOf()));
        Optional<String> inverseOf = pa.inverseOf().isEmpty() ? Optional.empty() : Optional.of(pa.inverseOf());
        OntologyRegistry.PropertyDef def = new OntologyRegistry.PropertyDef(
                pid, domainOpt, rangeOpt, inverseOf.isPresent(), inverseOf, pa.transitive(), pa.symmetric(), functional, superProps
        );
        props.put(pid, def);
    }

    private Optional<String> inferRangeFromReferenceTarget(Field field, Collection<Class<?>> annotatedClasses) {
        if (field == null) return Optional.empty();
        try {
            Class<?> rtAnno = Class.forName("com.e2eq.framework.model.persistent.base.ReferenceTarget");
            if (!field.isAnnotationPresent((Class) rtAnno)) return Optional.empty();
            var ann = field.getAnnotation((Class) rtAnno);
            var targetMethod = rtAnno.getMethod("target");
            Object targetClassObj = targetMethod.invoke(ann);
            if (targetClassObj instanceof Class<?> targetClass) {
                String id = annotatedClasses.contains(targetClass) ? classIdOf(targetClass) : targetClass.getSimpleName();
                return Optional.ofNullable(id);
            }
        } catch (Throwable ignored) { }
        return Optional.empty();
    }

    private Optional<String> inferRange(Class<?> rawType, Type genericType, Collection<Class<?>> annotatedClasses) {
        if (isCollectionOrArray(rawType)) {
            if (genericType instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length == 1 && args[0] instanceof Class<?> c && annotatedClasses.contains(c)) {
                    return Optional.of(classIdOf(c));
                }
            }
            return Optional.empty();
        }
        if (annotatedClasses.contains(rawType)) return Optional.of(classIdOf(rawType));
        return Optional.empty();
    }

    private boolean isCollectionOrArray(Class<?> t) {
        return t.isArray() || Collection.class.isAssignableFrom(t);
    }

    private String classIdOf(Class<?> clazz) {
        OntologyClass a = clazz.getAnnotation(OntologyClass.class);
        if (a != null && !a.id().isEmpty()) return a.id();
        return clazz.getSimpleName();
    }

    private String derivedNameFromMethod(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            String base = methodName.substring(3);
            return Character.toLowerCase(base.charAt(0)) + base.substring(1);
        }
        return methodName;
    }

    private Set<Class<?>> findClassesWithAnnotation(String packageName, Class<?> annotationType) {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> c : findClasses(packageName)) {
            if (c.getAnnotation((Class) annotationType) != null) result.add(c);
        }
        return result;
    }

    private Set<Class<?>> findClasses(String packageName) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        String path = packageName.replace('.', '/');
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(path);
            while (resources.hasMoreElements()) {
                URL res = resources.nextElement();
                String protocol = res.getProtocol();
                if ("file".equals(protocol)) {
                    String filePath = URLDecoder.decode(res.getFile(), StandardCharsets.UTF_8);
                    findClassesInDirectory(packageName, new File(filePath), classes);
                } else if ("jar".equals(protocol)) {
                    JarURLConnection conn = (JarURLConnection) res.openConnection();
                    try (JarFile jar = conn.getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith(path) && name.endsWith(".class") && !entry.isDirectory()) {
                                String className = name.replace('/', '.').substring(0, name.length() - 6);
                                addIfLoadable(classes, className);
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) { }
        return classes;
    }

    private void findClassesInDirectory(String packageName, File directory, Set<Class<?>> sink) {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                findClassesInDirectory(packageName + "." + file.getName(), file, sink);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                addIfLoadable(sink, className);
            }
        }
    }

    private void addIfLoadable(Set<Class<?>> sink, String className) {
        try {
            Class<?> cls = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            sink.add(cls);
        } catch (Throwable ignored) { }
    }
}

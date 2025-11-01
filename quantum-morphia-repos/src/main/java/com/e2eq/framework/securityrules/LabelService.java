package com.e2eq.framework.securityrules;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@ApplicationScoped
public class LabelService {
    @Inject
    Instance<LabelResolver> resolvers;

    public Set<String> labelsFor(Object entity) {
        if (entity == null) return Set.of();

        // 1) Annotation-based override
        Set<String> ann = resolveByAnnotation(entity);
        if (!ann.isEmpty()) return ann;

        // 2) Pluggable resolvers match on type
        if (resolvers != null) {
            for (LabelResolver r : resolvers) {
                try {
                    if (r.supports(entity.getClass())) {
                        Set<String> out = r.resolveLabels(entity);
                        return out == null ? Set.of() : out;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return Set.of();
    }

    private Set<String> resolveByAnnotation(Object entity) {
        Set<String> out = new LinkedHashSet<>();
        Class<?> cls = entity.getClass();
        LabelSource ls = cls.getAnnotation(LabelSource.class);
        if (ls != null && !ls.method().isBlank()) {
            try {
                Method m = cls.getMethod(ls.method());
                Object v = m.invoke(entity);
                addValues(out, v);
            } catch (Throwable ignored) {}
        }
        for (Field f : cls.getDeclaredFields()) {
            if (f.isAnnotationPresent(LabelField.class)) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(entity);
                    addValues(out, v);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void addValues(Set<String> out, Object v) {
        if (v == null) return;
        if (v instanceof String s) {
            out.add(s);
        } else if (v instanceof String[] arr) {
            for (String s2 : arr) if (s2 != null) out.add(s2);
        } else if (v instanceof Collection<?> c) {
            for (Object o : c) if (o != null) out.add(String.valueOf(o));
        }
    }
}

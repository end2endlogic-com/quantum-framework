package com.e2eq.framework.rest.filters;

import com.e2eq.framework.annotations.RealmName;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RequestRealmAdmission;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admits every realm a matched resource method takes from its path or query before the resource
 * runs, so no caller-supplied name reaches a datastore unregistered ({@link RequestRealmAdmission}).
 * A parameter names a realm when:
 *
 * <ul>
 *   <li>its {@code @PathParam} or {@code @QueryParam} name names one ({@code realm}, {@code realmId},
 *       {@code realmRefName}, ...; a pattern such as {@code realmRegEx} is not a realm), or</li>
 *   <li>it is marked {@link RealmName} (a realm under another name, such as a realm directory's
 *       {@code {refName}}; {@code registered = false} checks only the shape, for a registration).</li>
 * </ul>
 *
 * <p>The annotations are read from the resource method and from every method it overrides or
 * implements (JAX-RS annotations inherited from an interface or superclass), and from the fields and
 * setters of a {@code @BeanParam}. The value checked is the raw value the resource receives: one
 * with surrounding whitespace or control characters is refused, never admitted as its trimmed form.
 * The realm the request's principal resolved to is admitted too (a receiver whose own catalog does
 * not list a realm Auth resolved). A name in another case than its registered refName is refused:
 * resources read it as a database name.</p>
 */
@Provider
@Priority(SecurityFilter.SECURITY_FILTER_PRIORITY + 10)
public class RealmParameterAdmissionFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    @Inject
    RequestRealmAdmission admission;

    /** A realm-naming input of a resource method: where it is read from, its name, how it is admitted. */
    record RealmInput(boolean path, String name, boolean registered) {
    }

    private static final Map<Method, List<RealmInput>> INPUTS = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext requestContext) {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        if (method == null || admission == null) {
            return;
        }
        MultivaluedMap<String, String> path = requestContext.getUriInfo().getPathParameters();
        MultivaluedMap<String, String> query = requestContext.getUriInfo().getQueryParameters();
        Class<?> resourceClass = resourceInfo.getResourceClass();
        for (RealmInput input : inputs(resourceClass, method)) {
            admitAll(input.path() ? path.get(input.name()) : query.get(input.name()), input.registered());
        }
    }

    static List<RealmInput> inputs(Class<?> resourceClass, Method method) {
        return INPUTS.computeIfAbsent(method, m -> collect(resourceClass, m));
    }

    private static List<RealmInput> collect(Class<?> resourceClass, Method method) {
        Set<RealmInput> out = new LinkedHashSet<>();
        for (Method declared : overridden(resourceClass == null ? method.getDeclaringClass() : resourceClass, method)) {
            Annotation[][] parameters = declared.getParameterAnnotations();
            Class<?>[] types = declared.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                RealmName marker = find(parameters[i], RealmName.class);
                for (Annotation annotation : parameters[i]) {
                    add(out, annotation, marker);
                    if (annotation instanceof BeanParam) {
                        beanInputs(types[i], out);
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    /** {@code method} and every method of the class hierarchy and interfaces it overrides or implements. */
    static List<Method> overridden(Class<?> start, Method method) {
        List<Method> out = new ArrayList<>();
        out.add(method);
        Deque<Class<?>> pending = new ArrayDeque<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        if (start != null) {
            pending.add(start);
        }
        pending.add(method.getDeclaringClass());
        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (type == null || type == Object.class || !seen.add(type)) {
                continue;
            }
            try {
                Method candidate = type.getDeclaredMethod(method.getName(), method.getParameterTypes());
                if (!candidate.equals(method)) {
                    out.add(candidate);
                }
            } catch (NoSuchMethodException none) {
                // not declared here
            }
            if (type.getSuperclass() != null) {
                pending.add(type.getSuperclass());
            }
            for (Class<?> implemented : type.getInterfaces()) {
                pending.add(implemented);
            }
        }
        return out;
    }

    private static void beanInputs(Class<?> bean, Set<RealmInput> out) {
        for (Class<?> type = bean; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                addAll(out, field);
            }
            for (Method setter : type.getDeclaredMethods()) {
                addAll(out, setter);
            }
        }
    }

    private static void addAll(Set<RealmInput> out, AnnotatedElement element) {
        Annotation[] annotations = element.getAnnotations();
        RealmName marker = find(annotations, RealmName.class);
        for (Annotation annotation : annotations) {
            add(out, annotation, marker);
        }
    }

    private static void add(Set<RealmInput> out, Annotation annotation, RealmName marker) {
        if (annotation instanceof PathParam p && (marker != null || namesRealm(p.value()))) {
            out.add(new RealmInput(true, p.value(), marker == null || marker.registered()));
        } else if (annotation instanceof QueryParam q && (marker != null || namesRealm(q.value()))) {
            out.add(new RealmInput(false, q.value(), marker == null || marker.registered()));
        }
    }

    private static <A extends Annotation> A find(Annotation[] annotations, Class<A> type) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        return null;
    }

    /** A parameter whose name names a realm; a pattern over realm names does not. */
    static boolean namesRealm(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("realm") && !lower.contains("regex") && !lower.contains("pattern");
    }

    private void admitAll(List<String> values, boolean registered) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            // An absent or empty value names no realm (the resource applies its default); any other
            // value, whitespace only included, is checked exactly as the resource will read it.
            if (value != null && !value.isEmpty()) {
                admitOne(value, registered);
            }
        }
    }

    void admitOne(String value) {
        admitOne(value, true);
    }

    void admitOne(String value, boolean registered) {
        if (!registered) {
            admission.admitShape(value);
            return;
        }
        if (value.equals(principalRealm()) && !admission.reserved(value)) {
            return;
        }
        String admitted = admission.admit(value);
        if (!admitted.equals(value)) {
            throw RemotePrincipalResolutionException.of(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                    "The requested realm differs from its registered refName '" + admitted + "'");
        }
    }

    private static String principalRealm() {
        try {
            return SecurityContext.getPrincipalContext()
                    .map(pc -> pc.getDefaultRealm()).orElse(null);
        } catch (RuntimeException none) {
            return null;
        }
    }
}

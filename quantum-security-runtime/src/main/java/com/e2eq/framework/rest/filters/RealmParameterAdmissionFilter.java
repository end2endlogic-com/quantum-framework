package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RequestRealmAdmission;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Admits every realm a matched resource method takes from its path or query (a {@code @PathParam}
 * or {@code @QueryParam} whose name names a realm: {@code realm}, {@code realmId},
 * {@code realmRefName}, ...; a pattern such as {@code realmRegEx} is not a realm) before the
 * resource runs, so no caller-supplied name reaches a datastore unregistered
 * ({@link RequestRealmAdmission}). The realm the request's principal resolved to is admitted too
 * (a receiver whose own catalog does not list a realm Auth resolved). A name in another case than
 * its registered refName is refused: resources read it as a database name.
 */
@Provider
@Priority(SecurityFilter.SECURITY_FILTER_PRIORITY + 10)
public class RealmParameterAdmissionFilter implements ContainerRequestFilter {

    @Context
    ResourceInfo resourceInfo;

    @Inject
    RequestRealmAdmission admission;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        if (method == null || admission == null) {
            return;
        }
        MultivaluedMap<String, String> path = requestContext.getUriInfo().getPathParameters();
        MultivaluedMap<String, String> query = requestContext.getUriInfo().getQueryParameters();
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof PathParam p && namesRealm(p.value())) {
                    admitAll(path.get(p.value()));
                } else if (annotation instanceof QueryParam q && namesRealm(q.value())) {
                    admitAll(query.get(q.value()));
                }
            }
        }
    }

    /** A parameter whose name names a realm; a pattern over realm names does not. */
    static boolean namesRealm(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("realm") && !lower.contains("regex") && !lower.contains("pattern");
    }

    private void admitAll(List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                admitOne(value);
            }
        }
    }

    void admitOne(String value) {
        String trimmed = value.trim();
        if (trimmed.equals(principalRealm()) && !admission.reserved(trimmed)) {
            return;
        }
        String registered = admission.admit(trimmed);
        if (!registered.equals(trimmed)) {
            throw RemotePrincipalResolutionException.of(PrincipalResolutionError.REALM_NOT_AUTHORIZED,
                    "The requested realm differs from its registered refName '" + registered + "'");
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

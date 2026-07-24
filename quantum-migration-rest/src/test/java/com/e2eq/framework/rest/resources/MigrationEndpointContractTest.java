package com.e2eq.framework.rest.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MigrationEndpointContractTest {

    @Test
    void preservesMigrationAdministrationEndpoints() {
        assertEquals(Set.of(
                "GET /system/migration/dbversion/{realm}",
                "POST /system/migration/indexes/applyIndexes/{realm}",
                "POST /system/migration/indexes/applyIndexes/{realm}/{collection}",
                "POST /system/migration/indexes/applyAllIndexes/{realm}",
                "POST /system/migration/indexes/dropAllIndexes/{realm}",
                "POST /system/migration/indexes/drop/{realm}/{collection}",
                "POST /system/migration/changeSet/execute/{realm}/{beanRefName}",
                "POST /system/migration/initialize/{realm}",
                "GET /system/migration/start",
                "GET /system/migration/start/{realm}"),
                endpoints(MigrationResource.class));
    }

    private static Set<String> endpoints(Class<?> resourceClass) {
        String root = resourceClass.getAnnotation(Path.class).value();
        Set<String> endpoints = new LinkedHashSet<>();
        for (Method method : resourceClass.getDeclaredMethods()) {
            String verb = httpMethod(method);
            if (verb == null) {
                continue;
            }
            Path path = method.getAnnotation(Path.class);
            endpoints.add(verb + " " + join(root, path == null ? "" : path.value()));
        }
        return endpoints;
    }

    private static String httpMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod httpMethod = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (httpMethod != null) {
                return httpMethod.value();
            }
        }
        return null;
    }

    private static String join(String root, String child) {
        if (child == null || child.isBlank()) {
            return root;
        }
        return root.replaceAll("/+$", "") + "/" + child.replaceAll("^/+", "");
    }
}

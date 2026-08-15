package com.e2eq.framework.rest.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MigrationEndpointContractTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

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

    @Test
    void resolvesMigrationApplicationFromCurrentPrincipalBeforeSystemContextReplacement() {
        MigrationResource resource = new MigrationResource();
        resource.migrationApplicationId = Optional.of("fallback-app");
        SecurityContext.setPrincipalContext(principal("quantum-system"));

        assertEquals("quantum-system", resource.currentMigrationApplicationId());
    }

    @Test
    void fallsBackToConfiguredMigrationApplicationForGeneratedSystemCalls() {
        MigrationResource resource = new MigrationResource();
        resource.migrationApplicationId = Optional.of(" quantum-system ");

        assertEquals("quantum-system", resource.currentMigrationApplicationId());
    }

    @Test
    void allowsMissingConfiguredMigrationApplication() {
        MigrationResource resource = new MigrationResource();
        resource.migrationApplicationId = Optional.empty();

        assertEquals(null, resource.currentMigrationApplicationId());
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

    private static PrincipalContext principal(String applicationId) {
        return new PrincipalContext.Builder()
                .withDefaultRealm("quantum-auth")
                .withApplicationId(applicationId)
                .withDataDomain(new DataDomain("system.com", "0000000000", "system.com", 0, "system"))
                .withUserId("system")
                .withRoles(new String[]{"system", "admin"})
                .withScope("SYSTEM")
                .build();
    }
}

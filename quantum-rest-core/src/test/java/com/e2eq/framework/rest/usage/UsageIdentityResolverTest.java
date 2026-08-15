package com.e2eq.framework.rest.usage;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsageIdentityResolverTest {

    @Test
    void combinesFunctionalMappingAndExplicitOpenApiOperationId() throws Exception {
        Method method = GovernedResource.class.getDeclaredMethod("list");
        UsageEndpointIdentity identity = new UsageEndpointIdentityResolver().resolve(
                resourceInfo(GovernedResource.class, method), requestContext(Map.of()));

        assertEquals("sales:order:listOrders", identity.canonicalName());
    }

    @Test
    void modelFunctionalMappingPropertiesRemainAuthoritative() throws Exception {
        Method method = GovernedResource.class.getDeclaredMethod("list");
        UsageEndpointIdentity identity = new UsageEndpointIdentityResolver().resolve(
                resourceInfo(GovernedResource.class, method),
                requestContext(Map.of(
                        UsageEndpointIdentityResolver.MODEL_AREA, "inventory",
                        UsageEndpointIdentityResolver.MODEL_DOMAIN, "stock")));

        assertEquals("inventory:stock:listOrders", identity.canonicalName());
    }

    @Test
    void missingFunctionalMappingIsAnExplicitStateError() throws Exception {
        Method method = UnmappedResource.class.getDeclaredMethod("read");
        UsageEnforcementStateException exception = assertThrows(
                UsageEnforcementStateException.class,
                () -> new UsageEndpointIdentityResolver().resolve(
                        resourceInfo(UnmappedResource.class, method), requestContext(Map.of())));

        assertEquals(UsageEnforcementStateException.ENDPOINT_IDENTITY_UNAVAILABLE, exception.code());
    }

    @Test
    void principalUsesFrameworkEffectiveTenantAndSubject() {
        PrincipalContext context = principalContext(
                "effective-tenant", "effective-realm", "subject-42", "user-17");

        UsagePrincipalIdentity result = new UsagePrincipalIdentityResolver(
                () -> Optional.of(context)).resolve();

        assertEquals("effective-tenant", result.tenantId());
        assertEquals("subject-42", result.subjectId());
    }

    @Test
    void missingFrameworkPrincipalFailsWithTypedStateError() {
        UsageEnforcementStateException exception = assertThrows(
                UsageEnforcementStateException.class,
                () -> new UsagePrincipalIdentityResolver(Optional::empty).resolve());
        assertEquals(UsageEnforcementStateException.PRINCIPAL_IDENTITY_UNAVAILABLE, exception.code());
    }

    private static ResourceInfo resourceInfo(Class<?> resourceClass, Method method) {
        return new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return method;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        };
    }

    private static ContainerRequestContext requestContext(Map<String, Object> initialProperties) {
        Map<String, Object> properties = new HashMap<>(initialProperties);
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getProperty" -> properties.get(arguments[0]);
                    case "getPropertyNames" -> properties.keySet();
                    case "setProperty" -> {
                        properties.put((String) arguments[0], arguments[1]);
                        yield null;
                    }
                    case "removeProperty" -> properties.remove(arguments[0]);
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static PrincipalContext principalContext(
            String tenant, String realm, String subject, String userId) {
        DataDomain domain = new DataDomain("org", "account", tenant, 0, userId);
        return new PrincipalContext.Builder()
                .withDefaultRealm(realm)
                .withDataDomain(domain)
                .withUserId(userId)
                .withSubjectId(subject)
                .withRoles(new String[]{"user"})
                .withScope("authenticated")
                .build();
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalMapping(area = "SALES", domain = "ORDER")
    static final class GovernedResource {
        @Operation(operationId = "listOrders")
        void list() {
        }
    }

    static final class UnmappedResource {
        void read() {
        }
    }
}

package com.e2eq.framework.rest.resources;

import com.e2eq.framework.bootstrap.runtime.TenantInitializationFailure;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantApplicationInitializationResourceTest {
    TenantApplicationInitializationResource resource(String type, Set<String> audience, String caller, Set<String> roles) {
        var resource = new TenantApplicationInitializationResource();
        resource.applicationId = Optional.of("app"); resource.callerApplicationId = Optional.of("system-manager");
        resource.jwt = (JsonWebToken)Proxy.newProxyInstance(JsonWebToken.class.getClassLoader(),
            new Class<?>[]{JsonWebToken.class},(proxy, method, args) -> switch(method.getName()) {
                case "getSubject" -> "service-subject";
                case "getGroups" -> roles;
                case "getAudience" -> audience;
                case "getClaim" -> "azp".equals(args[0]) ? caller : type;
                default -> throw new AssertionError(method.getName());
            });
        return resource;
    }
    @Test void exactServiceIdentityAdmitted() {
        assertDoesNotThrow(() -> resource("service",Set.of(),"system-manager",Set.of("tenant-initializer")).authorizeService());
    }
    @Test void userWildcardMultiAudienceAndWrongCallerRejected() {
        var requests = List.of(
            resource("user",Set.of("app"),"system-manager",Set.of("tenant-initializer")),
            resource("service",Set.of("*"),"system-manager",Set.of("tenant-initializer")),
            resource("service",Set.of("app","other"),"system-manager",Set.of("tenant-initializer")),
            resource("service",Set.of(),"other",Set.of("tenant-initializer")),
            resource("service",Set.of(),"system-manager",Set.of("admin")));
        for (var request : requests) assertEquals("INITIALIZATION_SERVICE_IDENTITY_REQUIRED",
            assertThrows(TenantInitializationFailure.class, request::authorizeService).code());
    }
}

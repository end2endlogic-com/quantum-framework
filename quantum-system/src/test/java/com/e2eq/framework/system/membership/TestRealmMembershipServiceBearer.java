package com.e2eq.framework.system.membership;

import com.e2eq.framework.system.config.QuantumModeConfig;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

class TestRealmMembershipServiceBearer {

    @Test
    void requestBearerPrefersTheAuthenticatedCaller() {
        RealmMembershipService service = serviceWithJwt("caller-token", false);
        service.quantumModeConfig = withToken(Optional.of("service-token"));

        Assertions.assertEquals(Optional.of("caller-token"), service.requestBearerToken());
    }

    @Test
    void requestBearerUsesConfiguredServiceIdentityOutsideARequest() {
        RealmMembershipService service = serviceWithJwt(null, true);
        service.quantumModeConfig = withToken(Optional.of("service-token"));

        Assertions.assertEquals(Optional.of("service-token"), service.requestBearerToken());
    }

    @Test
    void requestBearerRemainsEmptyWithoutCallerOrServiceIdentity() {
        RealmMembershipService service = serviceWithJwt("  ", false);
        service.quantumModeConfig = withToken(Optional.empty());

        Assertions.assertEquals(Optional.empty(), service.requestBearerToken());
    }

    private static QuantumModeConfig withToken(Optional<String> token) {
        return QuantumModeConfig.of("remote", Optional.of("http://directory.test"),
            Optional.empty(), Optional.empty(), token);
    }

    private static RealmMembershipService serviceWithJwt(
        String rawToken,
        boolean failWithoutRequestContext
    ) {
        RealmMembershipService service = new RealmMembershipService();
        service.jwt = (JsonWebToken) Proxy.newProxyInstance(
            JsonWebToken.class.getClassLoader(),
            new Class<?>[] {JsonWebToken.class},
            (proxy, method, args) -> {
                if ("getRawToken".equals(method.getName())) {
                    if (failWithoutRequestContext) {
                        throw new IllegalStateException("no request context");
                    }
                    return rawToken;
                }
                if ("getName".equals(method.getName())) {
                    return "test-principal";
                }
                return null;
            });
        return service;
    }
}

package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PermissionRuleInterceptorTest {

    private final PermissionRuleInterceptor interceptor = new PermissionRuleInterceptor();

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @Test
    void contextlessPersistenceFailsClosed() {
        assertThrows(SecurityException.class,
                () -> interceptor.prePersist(new Object(), new Document(), null));
    }

    @Test
    void explicitIgnoreRulesScopeAllowsPrivilegedPersistence() {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            assertDoesNotThrow(() -> interceptor.prePersist(new Object(), new Document(), null));
        }
    }

    @Test
    void seedActionIsNotAnImplicitAuthorizationBypass() {
        ResourceContext seed = resource("seed");
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openResourceOnly(seed)) {
            assertThrows(SecurityException.class,
                    () -> interceptor.prePersist(new Object(), new Document(), null));
        }
    }

    @Test
    void writeActionWithoutPrincipalFailsClosed() {
        ResourceContext write = resource("write");
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openResourceOnly(write)) {
            assertThrows(SecurityException.class,
                    () -> interceptor.prePersist(new Object(), new Document(), null));
        }
    }

    private ResourceContext resource(String action) {
        return new ResourceContext.Builder()
                .withRealm("test-realm")
                .withArea("test")
                .withFunctionalDomain("persistence")
                .withAction(action)
                .build();
    }
}

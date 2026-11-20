package com.e2eq.framework.actionenablement.runtime.resolver;

import com.e2eq.framework.model.persistent.morphia.ReceivingApplication;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * At the identity authority with {@code quantum.auth.identity-authority.application-id} set, only a
 * token contract v2 user access token is evaluated for Auth's own application; a v1 token keeps its
 * azp, and a v1 or v2 service token (no azp) keeps no application, as SecurityFilter enforces it.
 */
class PermissionActionDependencyResolverApplicationTest {

    private static String applicationFor(SecurityIdentity identity) {
        PermissionActionDependencyResolver resolver = new PermissionActionDependencyResolver();
        resolver.securityIdentity = identity;
        resolver.receivingApplication = new ReceivingApplication() {
            @Override
            public String applicationId() {
                return null;
            }

            @Override
            public String identityAuthorityApplicationId() {
                return "quantum-system";
            }
        };
        return resolver.configuredApplicationId();
    }

    @Test
    void aV2AccessTokenTakesTheAuthoritysOwnApplication() {
        assertEquals("quantum-system", applicationFor(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "alice").addAttribute("token_type", "access").build()));
    }

    @Test
    void aV1TokenKeepsItsAzp() {
        assertEquals("signed-in-app", applicationFor(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "alice").addAttribute("azp", "signed-in-app").build()));
    }

    @Test
    void aV1ServiceTokenAtAuthKeepsNoApplication() {
        assertNull(applicationFor(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "svc").addRole("admin").build()));
    }

    @Test
    void aV2ServiceTokenAtAuthKeepsNoApplication() {
        assertNull(applicationFor(QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "svc").addAttribute("token_type", "service").build()));
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A request without credentials is anonymous, whatever it names. In particular a {@code tenantId}
 * query parameter (which an old, now removed, code path once turned into the realm's default
 * administrator with that administrator's stored roles) never selects an identity or roles: the
 * filter reads no credential and no realm for it.
 */
class SecurityFilterAnonymousRequestTest {

    @Test
    void aRequestWithoutCredentialsIsAnonymousEvenWhenItNamesATenant() {
        SecurityFilter filter = filter();

        PrincipalContext context = filter.determinePrincipalContext(
            request(Map.of(), Map.of("tenantId", "acme.com")));

        assertArrayEquals(new String[]{"ANONYMOUS"}, context.getRoles());
        assertEquals("anonymous@system.com", context.getUserId());
        assertEquals("systemGenerated", context.getScope());
        assertEquals("system.com", context.getDataDomain().getTenantId(),
            "the anonymous principal stays in the system data domain, not the named tenant");
        assertNotEquals("acme.com", context.getDataDomain().getTenantId());
    }

    private static SecurityFilter filter() {
        EnvConfigUtils env = new EnvConfigUtils();
        env.setSystemRealm("system-com");
        env.setAnonymousUserId("anonymous@system.com");
        env.setSystemOrgRefName("system.com");
        env.setSystemAccountNumber("0000000000");
        env.setSystemTenantId("system.com");
        env.setSystemUserId("system@system.com");
        SecurityUtils securityUtils = new SecurityUtils();
        securityUtils.setSystemDataDomain(new com.e2eq.framework.model.persistent.base.DataDomain(
            "system.com", "0000000000", "system.com", 0, "system@system.com"));

        SecurityFilter filter = new SecurityFilter();
        filter.envConfigUtils = env;
        filter.securityUtils = securityUtils;
        filter.securityIdentity = QuarkusSecurityIdentity.builder().setAnonymous(true).build();
        // No credential, realm or directory store: an anonymous request that looked one up would fail here.
        return filter;
    }

    private static ContainerRequestContext request(Map<String, String> headers, Map<String, String> query) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        query.forEach(params::add);
        UriInfo uri = (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(), new Class<?>[]{UriInfo.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getQueryParameters" -> params;
                case "getPath" -> "/things";
                default -> null;
            });
        return (ContainerRequestContext) Proxy.newProxyInstance(
            ContainerRequestContext.class.getClassLoader(), new Class<?>[]{ContainerRequestContext.class},
            (proxy, method, args) -> {
                if ("getHeaderString".equals(method.getName())) {
                    return headers.get(String.valueOf(args[0]));
                }
                if ("getUriInfo".equals(method.getName())) {
                    return uri;
                }
                if ("getMethod".equals(method.getName())) {
                    return "GET";
                }
                if (method.getReturnType().equals(boolean.class)) {
                    return false;
                }
                return null;
            });
    }
}

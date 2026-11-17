package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.principal.RemotePrincipalRequest;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSettings;
import com.e2eq.framework.security.principal.RemotePrincipalSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.core.HttpHeaders;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remote mode: @RolesAllowed sees the roles Auth resolves, never the token's groups. */
class RolesAugmentorRemotePrincipalTest {

    @Test
    void rolesComeFromAuthNotTheTokenGroups() {
        var source = new SecurityFilterRemotePrincipalSourceTest.RecordingSource(
                SecurityFilterRemotePrincipalSourceTest.principal("airfreight-demo-example"));
        RolesAugmentor augmentor = augmentor(source, Map.of("X-Tenant-Id", "tenant-x"));

        SecurityIdentity augmented = augmentor.augment(identity(), null).await().indefinitely();

        assertEquals(Set.of("user", "editor"), augmented.getRoles());
        assertEquals("mingardia", augmented.getAttribute("userId"));
        RemotePrincipalRequest sent = source.only();
        assertEquals("raw-token", sent.bearer());
        assertEquals("helixor-di", sent.applicationId());
        assertEquals("tenant-x", sent.tenantId());
        assertTrue(sent.tenantSelectionOptional(), "roles are asked before the resource's tenant rule is known");
    }

    @Test
    void aRefusalLeavesTheIdentityWithNoRoles() {
        RemotePrincipalSource refusing = request -> {
            throw new RemotePrincipalResolutionException(403, "APPLICATION_NOT_GRANTED", "no", "detail");
        };
        RolesAugmentor augmentor = augmentor(refusing, Map.of());

        SecurityIdentity augmented = augmentor.augment(identity(), null).await().indefinitely();

        assertTrue(augmented.getRoles().isEmpty(), augmented.getRoles().toString());
    }

    @Test
    void anUnreachableAuthLeavesTheIdentityWithNoRoles() {
        RemotePrincipalSource down = request -> {
            throw RemotePrincipalResolutionException.unavailable("connection refused");
        };
        RolesAugmentor augmentor = augmentor(down, Map.of());

        SecurityIdentity augmented = augmentor.augment(identity(), null).await().indefinitely();

        assertTrue(augmented.getRoles().isEmpty(), augmented.getRoles().toString());
    }

    private static RolesAugmentor augmentor(RemotePrincipalSource source, Map<String, String> headers) {
        RolesAugmentor augmentor = new RolesAugmentor();
        augmentor.remotePrincipalSettings = new RemotePrincipalSettings() {
            @Override public boolean remote() { return true; }
            @Override public String applicationId() { return "helixor-di"; }
            @Override public RemotePrincipalSource source() { return source; }
        };
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> "getHeaderString".equals(method.getName())
                        ? headers.get(String.valueOf(args[0])) : null);
        // credentialRepo, role resolver and directories stay null: remote mode must not reach them.
        return augmentor;
    }

    private static SecurityIdentity identity() {
        JsonWebToken token = (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(), new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClaim" -> Map.of("iss", "https://auth.example.test", "sub", "subject-123")
                            .get(String.valueOf(args[0]));
                    case "getName" -> "subject-123";
                    case "getRawToken" -> "raw-token";
                    case "getGroups" -> Set.of("token-admin");
                    default -> null;
                });
        return QuarkusSecurityIdentity.builder().setPrincipal(token).addRoles(Set.of("token-admin")).build();
    }
}

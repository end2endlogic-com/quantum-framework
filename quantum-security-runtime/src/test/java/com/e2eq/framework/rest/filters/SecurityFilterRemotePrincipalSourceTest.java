package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.principal.PrincipalRef;
import com.e2eq.framework.security.principal.RemotePrincipalRequest;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSettings;
import com.e2eq.framework.security.principal.RemotePrincipalSource;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code quantum.auth.principal-source=remote}: SecurityFilter asks the remote source who the
 * verified bearer is for this application and reads nothing else from the token.
 */
class SecurityFilterRemotePrincipalSourceTest {

    private static final String ISS = "https://auth.example.test";
    private static final String SUB = "subject-123";
    private static final String APP = "helixor-di";

    /** Claims a foreign-realm token carries today; remote mode must not read any of them. */
    private static final Map<String, Object> CLAIMS = Map.of(
            "iss", ISS, "sub", SUB,
            "realm", "helixor-di-D1", "tenantId", "helixor-di-D1", "azp", "helixor-launchpad",
            "userId", "mingardia", "token_type", "access");

    @Test
    void wrongTenantClaimGetsTheTenantAuthResolves() {
        RecordingSource source = new RecordingSource(principal("airfreight-demo-example"));
        // The filter runs the delegated-claims configuration that refuses this token today.
        SecurityFilter filter = filter(source, CLAIMS);
        filter.trustTokenClaims = true;

        PrincipalContext context = filter.determinePrincipalContext(request(Map.of("Authorization", "Bearer raw-token")));

        assertEquals("airfreight-demo-example", context.getDataDomain().getTenantId());
        assertEquals("airfreight-demo", context.getDefaultRealm());
        assertArrayEquals(new String[]{"user", "editor"}, context.getRoles());
        assertEquals(SUB, context.getSubjectId());
        RemotePrincipalRequest sent = source.only();
        assertEquals("raw-token", sent.bearer());
        assertEquals(ISS, sent.issuer());
        assertEquals(SUB, sent.subject());
        assertEquals(APP, sent.applicationId());
        assertNull(sent.realm());
        assertNull(sent.tenantId());
        assertNull(sent.impersonate());
        assertNull(sent.actingOnBehalfOf());
    }

    @Test
    void requestSelectionsAreSentInTheRequestNotReadFromTheToken() {
        RecordingSource source = new RecordingSource(principal("tenant-b"));
        SecurityFilter filter = filter(source, CLAIMS);

        filter.determinePrincipalContext(request(Map.of(
                "Authorization", "Bearer raw-token",
                "X-Realm", "realm-b",
                "X-Tenant-Id", "tenant-b",
                "X-Impersonate-UserId", "target-user")));

        RemotePrincipalRequest sent = source.only();
        assertEquals("realm-b", sent.realm());
        assertEquals("tenant-b", sent.tenantId());
        assertEquals(new PrincipalRef("target-user", null), sent.impersonate());
    }

    @Test
    void serviceTokensResolveRemotelyToo() {
        Map<String, Object> service = new HashMap<>(CLAIMS);
        service.put("token_type", "service");
        RecordingSource source = new RecordingSource(principal("airfreight-demo-example"));
        SecurityFilter filter = filter(source, service);

        filter.determinePrincipalContext(request(Map.of(
                "Authorization", "Bearer raw-token",
                "X-Acting-On-Behalf-Of-Subject", "acted-for")));

        RemotePrincipalRequest sent = source.only();
        assertEquals(APP, sent.applicationId());
        assertEquals(new PrincipalRef(null, "acted-for"), sent.actingOnBehalfOf());
    }

    @Test
    void refusalPassesThroughWithAuthsStatusAndCode() {
        RemotePrincipalResolutionException refusal = new RemotePrincipalResolutionException(
                403, "TENANT_NOT_AUTHORIZED", "The identity is not authorized for the tenant", "detail");
        SecurityFilter filter = filter(new ThrowingSource(refusal), CLAIMS);
        filter.trustTokenClaims = true;

        RemotePrincipalResolutionException thrown = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.determinePrincipalContext(request(Map.of("Authorization", "Bearer raw-token"))));

        assertSame(refusal, thrown);
        assertEquals(403, thrown.getResponse().getStatus());
        assertEquals("TENANT_NOT_AUTHORIZED", thrown.code());
    }

    @Test
    void unavailableFailsClosedWithoutTheClaimsPath() {
        // trust-token-claims on and every collaborator of the claims and credential paths null:
        // a fallback would either succeed from claims or fail with another exception.
        SecurityFilter filter = filter(new ThrowingSource(RemotePrincipalResolutionException.unavailable("down")), CLAIMS);
        filter.trustTokenClaims = true;

        RemotePrincipalResolutionException thrown = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.determinePrincipalContext(request(Map.of("Authorization", "Bearer raw-token"))));

        assertEquals(503, thrown.getResponse().getStatus());
        assertEquals("PRINCIPAL_RESOLUTION_UNAVAILABLE", thrown.code());
    }

    @Test
    void anIdentityNamedTwiceIsRefusedBeforeAuthIsAsked() {
        RecordingSource source = new RecordingSource(principal("t"));
        SecurityFilter filter = filter(source, CLAIMS);

        RemotePrincipalResolutionException thrown = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter.determinePrincipalContext(request(Map.of(
                        "Authorization", "Bearer raw-token",
                        "X-Impersonate-UserId", "a",
                        "X-Impersonate-Subject", "b"))));

        assertEquals(400, thrown.getResponse().getStatus());
        assertEquals("INVALID_REQUEST", thrown.code());
        assertTrue(source.requests.isEmpty());
    }

    @Test
    void aRemoteContextKeepsItsOwnPropertiesSuchAsTheActingService() {
        PrincipalContext withService = new PrincipalContext.Builder()
                .withDefaultRealm("r").withUserId("u").withSubjectId(SUB).withRoles(new String[]{"user"})
                .withScope("AUTHENTICATED").withApplicationId(APP)
                .withDataDomain(dataDomain("t"))
                .withCustomProperty("actingService", Map.of("userId", "svc", "subject", "svc-sub"))
                .build();
        SecurityFilter filter = filter(new RecordingSource(withService), CLAIMS);

        PrincipalContext context = filter.determinePrincipalContext(request(Map.of("Authorization", "Bearer raw-token")));

        assertEquals(Map.of("userId", "svc", "subject", "svc-sub"), context.getCustomProperty("actingService"));
    }

    @Test
    void unsetModeKeepsTheExistingPath() {
        RemotePrincipalSettings unset = new RemotePrincipalSettings();
        assertEquals(false, unset.remote());
    }

    // ------------------------------------------------------------------ fixtures

    private static SecurityFilter filter(RemotePrincipalSource source, Map<String, Object> claims) {
        SecurityFilter filter = new SecurityFilter();
        JsonWebToken token = identityOnlyJwt(claims);
        filter.jwt = token;
        filter.securityIdentity = QuarkusSecurityIdentity.builder().setPrincipal(token)
                .addRoles(Set.of("token-role")).build();
        filter.remotePrincipalSettings = new RemotePrincipalSettings() {
            @Override public boolean remote() { return true; }
            @Override public String applicationId() { return APP; }
            @Override public RemotePrincipalSource source() { return source; }
        };
        return filter;
    }

    static PrincipalContext principal(String tenantId) {
        return principal(tenantId, "user", "editor");
    }

    static PrincipalContext principal(String tenantId, String... roles) {
        return new PrincipalContext.Builder()
                .withDefaultRealm("airfreight-demo")
                .withApplicationId(APP)
                .withUserId("mingardia")
                .withSubjectId(SUB)
                .withRoles(roles)
                .withScope("AUTHENTICATED")
                .withDomainContext(DomainContext.builder().tenantId(tenantId).defaultRealm("airfreight-demo")
                        .orgRefName("org").accountId("acct").build())
                .withDataDomain(dataDomain(tenantId))
                .build();
    }

    private static DataDomain dataDomain(String tenantId) {
        DataDomain domain = new DataDomain();
        domain.setOrgRefName("org");
        domain.setAccountNum("acct");
        domain.setTenantId(tenantId);
        domain.setOwnerId("mingardia");
        return domain;
    }

    /** A verified token that fails the test if anything beyond iss and sub is read from it. */
    private static JsonWebToken identityOnlyJwt(Map<String, Object> claims) {
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClaim" -> {
                        String name = String.valueOf(args[0]);
                        if (!"iss".equals(name) && !"sub".equals(name)) {
                            throw new AssertionError("remote mode read token claim '" + name + "'");
                        }
                        yield claims.get(name);
                    }
                    case "getName" -> claims.get("sub");
                    case "getRawToken" -> "raw-token";
                    case "getGroups", "getClaimNames", "getAudience" ->
                            throw new AssertionError("remote mode read " + method.getName());
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "jwt";
                    default -> null;
                });
    }

    private static ContainerRequestContext request(Map<String, String> headers) {
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> {
                    if ("getHeaderString".equals(method.getName())) {
                        return headers.get(String.valueOf(args[0]));
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    return null;
                });
    }

    static final class RecordingSource implements RemotePrincipalSource {
        final List<RemotePrincipalRequest> requests = new ArrayList<>();
        private final PrincipalContext answer;

        RecordingSource(PrincipalContext answer) {
            this.answer = answer;
        }

        @Override
        public PrincipalContext resolve(RemotePrincipalRequest request) {
            requests.add(request);
            return answer;
        }

        RemotePrincipalRequest only() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }
    }

    record ThrowingSource(RuntimeException failure) implements RemotePrincipalSource {
        @Override
        public PrincipalContext resolve(RemotePrincipalRequest request) {
            throw failure;
        }
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.principal.RemotePrincipalRequest;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSource;
import com.e2eq.framework.system.membership.RealmMembershipService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code quantum.auth.principal-source=remote} end to end: real JWT verification, the roles
 * augmentor, SecurityFilter and the refusal mapper, with the remote source a test double. The
 * token carries the claims a foreign-realm token carries today (a placeholder tenant, issuer-only
 * groups); none of them decides anything.
 */
@QuarkusTest
@TestProfile(RemotePrincipalSourceHttpTest.RemoteProfile.class)
class RemotePrincipalSourceHttpTest {

    public static final class RemoteProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quantum.auth.principal-source", "remote",
                    "quantum.security.session.application-id", "helixor-di",
                    "quantum.security.session.realm", "");
        }
    }

    @InjectMock CredentialRepo credentials;
    @InjectMock AuthProviderFactory providers;
    @InjectMock SystemDirectory directory;
    @InjectMock RealmMembershipService memberships;
    @InjectMock com.e2eq.framework.model.persistent.migration.base.MigrationService migrations;
    @TestHTTPResource URI base;

    @BeforeEach
    void reset() {
        StubSource.requests.clear();
        StubSource.answer = request -> principal("airfreight-demo-example", "editor");
    }

    @Test
    void theTenantAndRolesAreAuthsNotTheTokens() throws Exception {
        HttpResponse<String> response = call("remote-principal/roles", Map.of());

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("mingardia:airfreight-demo:helixor-di:airfreight-demo-example:editor", response.body());
        assertEquals(1, StubSource.requests.size(), "augmentor and filter share one resolution per request");
        RemotePrincipalRequest sent = StubSource.requests.get(0);
        assertEquals("helixor-di", sent.applicationId());
        assertEquals("subject-123", sent.subject());
        assertEquals("https://lifecycle.invalid", sent.issuer());
        verifyNoInteractions(credentials, providers, directory, memberships);
    }

    @Test
    void theTokensGroupsDoNotSatisfyRolesAllowed() throws Exception {
        StubSource.answer = request -> principal("airfreight-demo-example", "viewer");

        assertEquals(403, call("remote-principal/roles", Map.of()).statusCode());
    }

    @Test
    void requestSelectionsReachTheSource() throws Exception {
        call("remote-principal/open", Map.of("X-Realm", "realm-b", "X-Tenant-Id", "tenant-b"));

        RemotePrincipalRequest sent = StubSource.requests.get(0);
        assertEquals("realm-b", sent.realm());
        assertEquals("tenant-b", sent.tenantId());
    }

    @Test
    void aTypedRefusalAnswersWithAuthsStatusAndCode() throws Exception {
        StubSource.answer = request -> {
            throw new RemotePrincipalResolutionException(403, "TENANT_NOT_AUTHORIZED",
                    "The identity is not authorized for the tenant", "detail");
        };

        HttpResponse<String> response = call("remote-principal/open", Map.of());

        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"TENANT_NOT_AUTHORIZED\""), response.body());
    }

    @Test
    void authUnavailableFailsClosedWith503() throws Exception {
        StubSource.answer = request -> {
            throw RemotePrincipalResolutionException.unavailable("connection refused");
        };

        HttpResponse<String> response = call("remote-principal/open", Map.of());

        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"PRINCIPAL_RESOLUTION_UNAVAILABLE\""), response.body());
        verifyNoInteractions(credentials, providers, directory, memberships);
    }

    private HttpResponse<String> call(String path, Map<String, String> headers) throws Exception {
        String token = Jwt.issuer("https://lifecycle.invalid").subject("subject-123").upn("mingardia")
                .groups(Set.of("editor")).claim("userId", "mingardia")
                .claim("realm", "helixor-di-D1").claim("tenantId", "helixor-di-D1")
                .claim("azp", "helixor-launchpad").expiresIn(60).sign();
        var request = HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static PrincipalContext principal(String tenantId, String role) {
        return SecurityFilterRemotePrincipalSourceTest.principal(tenantId, role);
    }

    /** The remote source double: present in every test of this module, used only in remote mode. */
    @ApplicationScoped
    public static class StubSource implements RemotePrincipalSource {
        static final List<RemotePrincipalRequest> requests = new CopyOnWriteArrayList<>();
        static volatile Function<RemotePrincipalRequest, PrincipalContext> answer;

        @Override
        public PrincipalContext resolve(RemotePrincipalRequest request) {
            requests.add(request);
            return answer.apply(request);
        }
    }

    @Path("/remote-principal")
    @com.e2eq.framework.annotations.FunctionalMapping(area = "TEST", domain = "REMOTE")
    public static class Resource {

        @GET
        @Path("/roles")
        @Produces("text/plain")
        @RolesAllowed("editor")
        @com.e2eq.framework.annotations.FunctionalAction(value = "view", bypassDataScoping = true)
        public String roles() {
            return describe();
        }

        @GET
        @Path("/open")
        @Produces("text/plain")
        @io.quarkus.security.Authenticated
        public String open() {
            return describe();
        }

        private static String describe() {
            PrincipalContext p = SecurityContext.getPrincipalContext().orElseThrow();
            return p.getUserId() + ":" + p.getDefaultRealm() + ":" + p.getApplicationId() + ":"
                    + p.getDataDomain().getTenantId() + ":" + String.join(",", p.getRoles());
        }
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.ClaimsAuthProvider;
import com.e2eq.framework.model.auth.ProviderClaims;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.system.membership.RealmMembershipService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JWT verification, augmentation and REST filters; directory/provider seams are test doubles. */
@QuarkusTest
class AuthorizationLifecycleHttpTest {
    @InjectMock CredentialRepo credentials;
    @InjectMock AuthProviderFactory providers;
    @InjectMock SystemDirectory directory;
    @InjectMock RealmMembershipService memberships;
    @InjectMock com.e2eq.framework.model.persistent.migration.base.MigrationService migrations;
    @Inject JWTParser parser;
    @TestHTTPResource URI base;
    private final AtomicInteger roleReads = new AtomicInteger();
    private Realm realm;

    @BeforeEach
    void prepare() throws Exception {
        roleReads.set(0);
        when(credentials.findBySubject(anyString(), anyString(), anyBoolean()))
                .thenThrow(new AssertionError("Delegated mode must not read local credentials"));
        when(directory.findCredentialBySubject(anyString()))
                .thenThrow(new IllegalStateException("Remote directory does not expose credentials"));
        realm = new Realm();
        realm.setRefName("tenant-a");
        realm.setApplicationRef(EntityReference.builder().entityRefName("app-a").entityDisplayName("app-a").build());
        realm.setDomainContext(DomainContext.builder().defaultRealm("tenant-a").tenantId("t-a")
                .orgRefName("org-a").accountId("account-a").build());
        when(directory.findRealmByRefName("tenant-a")).thenReturn(Optional.of(realm));
        when(memberships.assignmentForUser(anyString(), eq("tenant-a"))).thenAnswer(call -> {
            assertFalse(io.vertx.core.Context.isOnEventLoopThread(), "Role authority lookup must run on a worker");
            roleReads.incrementAndGet();
            var assignment = new UserRealmRole();
            assignment.setRoles(List.of("editor"));
            assignment.setAuthorizedApplications(List.of());
            return Optional.of(assignment);
        });
        var provider = mock(ClaimsAuthProvider.class);
        when(provider.validateTokenToClaims(anyString())).thenAnswer(call -> {
            var token = parser.parse(call.getArgument(0));
            return ProviderClaims.builder(token.getSubject()).issuer(token.getIssuer()).username(token.getClaim("userId"))
                    .tokenRoles(token.getGroups()).putAttribute("userId", token.getClaim("userId"))
                    .putAttribute("realm", token.getClaim("realm")).putAttribute("azp", token.getClaim("azp"))
                    .putAttribute("tenantId", "t-a").putAttribute("orgRefName", "org-a")
                    .putAttribute("accountNum", "account-a").build();
        });
        when(providers.getAuthProvider()).thenReturn(provider);
    }

    @Test
    void augmentationAndFilterShareOnlyThisRequestsRolesAcrossAsyncCompletion() throws Exception {
        var first = call("alice", null);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("alice:tenant-a:app-a:editor", first.body());
        assertEquals(1, roleReads.get(), "augmentor/filter must share matching delegated role resolution");
        var second = call("bob", null);
        assertEquals(200, second.statusCode(), second.body());
        assertEquals("bob:tenant-a:app-a:editor", second.body());
        assertEquals(2, roleReads.get(), "another request must resolve its own authorities");
        verifyNoInteractions(credentials);
    }

    @Test
    void cachedRolesDoNotBypassApplicationOrTenantAdmission() throws Exception {
        realm.setApplicationRef(EntityReference.builder().entityRefName("another-app").entityDisplayName("another-app").build());
        assertEquals(403, call("alice", null).statusCode());
        realm.setApplicationRef(EntityReference.builder().entityRefName("app-a").entityDisplayName("app-a").build());
        assertEquals(403, call("alice", "other-tenant").statusCode());
    }

    @Test
    void laterRequestObservesRevokedRoles() throws Exception {
        assertEquals(200, call("alice", null).statusCode());
        when(memberships.assignmentForUser("alice", "tenant-a")).thenReturn(Optional.empty());
        assertEquals(403, call("alice", null).statusCode());
        verifyNoInteractions(credentials);
    }

    protected HttpResponse<String> call(String user, String tenant) throws Exception {
        return call(user, tenant, null);
    }

    protected HttpResponse<String> call(String user, String tenant, String requestedRealm) throws Exception {
        String token = Jwt.issuer("https://lifecycle.invalid").subject(user).upn(user)
                .groups(Set.of("issuer-only")).claim("userId", user).claim("realm", "tenant-a")
                .claim("azp", "source-app").expiresIn(60).sign();
        var request = HttpRequest.newBuilder(base.resolve("lifecycle"))
                .header("Authorization", "Bearer " + token);
        if (tenant != null) request.header("X-Tenant-Id", tenant);
        if (requestedRealm != null) request.header("X-Realm", requestedRealm);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Path("/lifecycle")
    @com.e2eq.framework.annotations.FunctionalMapping(area = "TEST", domain = "LIFECYCLE")
    public static class Resource {
        @Inject io.vertx.ext.web.RoutingContext routing;

        @GET
        @Produces("text/plain")
        @RolesAllowed("editor")
        @com.e2eq.framework.annotations.FunctionalAction(value = "view", bypassDataScoping = true)
        @io.smallrye.common.annotation.Blocking
        public Uni<String> read() {
            var state = RequestAuthorizationState.from(routing);
            var completed = state.completed();
            if (completed == null) throw new IllegalStateException("REST admission did not publish context");
            return Uni.createFrom().completionStage(() -> CompletableFuture.supplyAsync(() -> {
                assertSame(completed, state.completed(), "completed context survives an asynchronous continuation");
                return completed.userId() + ":" + completed.realm() + ":" + completed.applicationId()
                        + ":" + String.join(",", completed.roles());
            }));
        }
    }
}

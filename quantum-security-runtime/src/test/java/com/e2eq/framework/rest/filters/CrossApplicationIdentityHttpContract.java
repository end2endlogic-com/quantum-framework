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
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs unchanged in two application configurations. This is shared-identity HTTP coverage, not browser SSO. */
abstract class CrossApplicationIdentityHttpContract {
    @InjectMock CredentialRepo credentials;
    @InjectMock AuthProviderFactory providers;
    @InjectMock SystemDirectory directory;
    @InjectMock RealmMembershipService memberships;
    @InjectMock com.e2eq.framework.model.persistent.migration.base.MigrationService migrations;
    @Inject JWTParser parser;
    @TestHTTPResource URI base;
    private UserRealmRole assignment;
    private ClaimsAuthProvider provider;
    protected abstract String application();

    static String sharedToken() throws Exception {
        return fixture("shared-identity-test.jwt");
    }

    private static String fixture(String name) throws Exception {
        try (var stream = CrossApplicationIdentityHttpContract.class.getResourceAsStream("/" + name)) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    @BeforeEach
    void prepare() throws Exception {
        when(directory.findCredentialBySubject(anyString())).thenThrow(new IllegalStateException("Remote mode"));
        var realm = new Realm();
        realm.setRefName("tenant-a");
        // Neither application gets implicit admission as the realm's home application.
        realm.setApplicationRef(EntityReference.builder().entityRefName("management").entityDisplayName("Management").build());
        realm.setDomainContext(DomainContext.builder().defaultRealm("tenant-a").tenantId("t-a")
                .orgRefName("org-a").accountId("account-a").build());
        when(directory.findRealmByRefName("tenant-a")).thenReturn(Optional.of(realm));
        assignment = new UserRealmRole();
        assignment.setRoles(List.of("app-reader"));
        assignment.setAuthorizedApplications(List.of("app-a", "app-b"));
        when(memberships.assignmentForUser("alice", "tenant-a")).thenReturn(Optional.of(assignment));
        provider = mock(ClaimsAuthProvider.class);
        when(provider.validateTokenToClaims(anyString())).thenAnswer(call -> {
            var jwt = parser.parse(call.getArgument(0));
            return ProviderClaims.builder(jwt.getSubject()).issuer(jwt.getIssuer()).username(jwt.getClaim("userId"))
                    .tokenRoles(jwt.getGroups()).putAttribute("userId", jwt.getClaim("userId"))
                    .putAttribute("realm", jwt.getClaim("realm")).putAttribute("azp", jwt.getClaim("azp"))
                    .putAttribute("tenantId", "t-a").putAttribute("orgRefName", "org-a")
                    .putAttribute("accountNum", "account-a").build();
        });
        when(providers.getAuthProvider()).thenReturn(provider);
    }

    @Test
    void identicalSignedIdentityWorksWithoutApplicationTokenRewritingOrLogin() throws Exception {
        String token = sharedToken();
        assertEquals("54106a6e665ddb5876c9972a55b179a033f044775538fc5146ccdd610c945f97", sha256(token),
                "Both apps must keep using the frozen identity contract fixture");
        var result = call("read", token);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals("shared-subject:identity-client:" + application() + ":app-reader:" + sha256(token), result.body());
        verify(provider).validateTokenToClaims(token);
        assertTrue(mockingDetails(provider).getInvocations().stream()
                .allMatch(call -> call.getMethod().getName().equals("validateTokenToClaims")), "No login, refresh or minting");
        verifyNoInteractions(credentials);
    }

    @Test
    void validAuthenticationDoesNotImplyApplicationAdmission() throws Exception {
        String token = sharedToken();
        assertEquals("shared-subject", parser.parse(token).getSubject());
        // Revoke only A: B's independently configured session must remain admitted.
        assignment.setAuthorizedApplications(List.of("app-b"));
        var result = call("read", token);
        assertEquals(application().equals("app-a") ? 403 : 200, result.statusCode(), result.body());
        verify(provider).validateTokenToClaims(token);
        assignment.setAuthorizedApplications(List.of("app-a", "app-b"));
        assertEquals(200, call("read", token).statusCode(), "Grant changes need no new token");
        verifyNoInteractions(credentials);
    }

    @Test
    void tokenRolesCannotEscalateTargetApplicationAndRevocationNeedsNoNewToken() throws Exception {
        String token = sharedToken();
        assertTrue(parser.parse(token).getGroups().contains("admin"));
        assertEquals(403, call("admin", token).statusCode(), "Issuer-client admin is not target-application admin");
        assertEquals(200, call("read", token).statusCode());
        assignment.setRoles(List.of());
        assertEquals(403, call("read", token).statusCode(), "Signed app-reader cannot override removed authority");
        assertEquals("shared-subject", parser.parse(token).getSubject(), "Identity still authenticates");
        assignment.setRoles(List.of("app-reader"));
        assertEquals(200, call("read", token).statusCode());
    }

    @Test
    void appIssuedTokenUsesSignedRolesAtItsOriginAndCurrentRolesAtTheReceiver() throws Exception {
        String token = fixture("app-a-identity-test.jwt");
        assertEquals("8e9294e43c77174a92a45dd08d483f30871dd0428504808d8a0f5decc5176393", sha256(token));
        assertEquals("app-a", parser.parse(token).getClaim("azp"));
        boolean issuingApplication = application().equals("app-a");
        assertEquals(200, call("read", token).statusCode());
        assertEquals(issuingApplication ? 200 : 403, call("admin", token).statusCode(),
                "Signed origin roles must not become receiving-application roles");

        assignment.setRoles(List.of());
        var afterRevocation = call("read", token);
        assertEquals(issuingApplication ? 200 : 403, afterRevocation.statusCode(), afterRevocation.body());
        // Compatibility characterization, not a claim of immediate origin-role revocation:
        // a trusted provider may still accept the original signed role snapshot at its origin.
        assertEquals("shared-subject", parser.parse(token).getSubject());
        assignment.setRoles(List.of("app-reader"));
        assertEquals(200, call("read", token).statusCode());
        verifyNoInteractions(credentials);
    }

    @Test
    void appIssuedTokenRechecksReceivingApplicationGrantButRetainsOriginAdmission() throws Exception {
        String token = fixture("app-a-identity-test.jwt");
        assertEquals(200, call("read", token).statusCode());
        assignment.setAuthorizedApplications(List.of());
        var afterRevocation = call("read", token);
        assertEquals(application().equals("app-a") ? 200 : 403,
                afterRevocation.statusCode(), afterRevocation.body());
        assignment.setAuthorizedApplications(List.of("app-a", "app-b"));
        assertEquals(200, call("read", token).statusCode());
        verifyNoInteractions(credentials);
    }

    @Test
    void authenticationEnforcesIssuerAudienceExpiryAndSignatureBeforeAuthority() throws Exception {
        String token = sharedToken();
        int signature = token.lastIndexOf('.') + 1;
        String tampered = token.substring(0, signature) + (token.charAt(signature) == 'A' ? 'B' : 'A') + token.substring(signature + 1);
        for (String rejected : List.of(tampered,
                rejectedToken("https://another-environment.invalid", "shared-test-api", false),
                rejectedToken("https://lifecycle.invalid", "another-api", false),
                rejectedToken("https://lifecycle.invalid", "shared-test-api", true))) {
            var result = call("read", rejected);
            assertEquals(401, result.statusCode(), result.body());
        }
        verifyNoInteractions(provider, memberships, credentials);
    }

    private static String rejectedToken(String issuer, String audience, boolean expired) {
        long now = Instant.now().getEpochSecond();
        return Jwt.issuer(issuer).subject("shared-subject").upn("alice").audience(audience)
                .groups(Set.of("admin", "app-reader")).claim("userId", "alice").claim("realm", "tenant-a")
                .claim("azp", "identity-client").issuedAt(now - 3600).expiresAt(expired ? now - 120 : now + 60).sign();
    }

    private HttpResponse<String> call(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("shared-identity/" + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @Path("/shared-identity")
    @com.e2eq.framework.annotations.FunctionalMapping(area = "TEST", domain = "SHARED_IDENTITY")
    public static class Resource {
        @Inject io.vertx.ext.web.RoutingContext routing;
        @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;
        @GET @Path("read") @RolesAllowed("app-reader")
        @com.e2eq.framework.annotations.FunctionalAction(value = "read", bypassDataScoping = true)
        @io.smallrye.common.annotation.Blocking
        public String read() throws Exception {
            var context = RequestAuthorizationState.from(routing).completed();
            return jwt.getSubject() + ":" + jwt.getClaim("azp") + ":" + context.applicationId()
                    + ":" + String.join(",", context.roles()) + ":" + sha256(jwt.getRawToken());
        }
        @GET @Path("admin") @RolesAllowed("admin")
        @com.e2eq.framework.annotations.FunctionalAction(value = "admin", bypassDataScoping = true)
        @io.smallrye.common.annotation.Blocking
        public String admin() { return "authorized-admin"; }
    }
}

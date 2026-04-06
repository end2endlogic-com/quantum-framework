package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import io.smallrye.mutiny.Uni;

import java.io.InputStream;
import java.net.URI;
import java.security.Principal;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationCheckRequestFilterTest {

    @Test
    void usesAuthenticatedUsersDefaultRealmWhenXRealmMissing() throws Exception {
        TestMigrationService migrationService = new TestMigrationService();
        MigrationCheckRequestFilter filter = buildFilter(
            identity("tenant-admin@example.com"),
            testEnvConfig(),
            new TestCredentialRepo("tenant-admin@example.com", "tenant-home-realm"),
            migrationService,
            null
        );

        filter.filter(new StubRequestContext("/api/orders", "GET"));

        assertEquals(List.of("tenant-home-realm", "system-com"), migrationService.checkedRealms);
    }

    @Test
    void usesXRealmWhenProvided() throws Exception {
        TestMigrationService migrationService = new TestMigrationService();
        MigrationCheckRequestFilter filter = buildFilter(
            identity("tenant-admin@example.com"),
            testEnvConfig(),
            new TestCredentialRepo("tenant-admin@example.com", "tenant-home-realm"),
            migrationService,
            null
        );

        StubRequestContext request = new StubRequestContext("/api/orders", "GET");
        request.getHeaders().putSingle("X-Realm", "override-realm");
        filter.filter(request);

        assertEquals(List.of("override-realm", "system-com"), migrationService.checkedRealms);
    }

    @Test
    void fallsBackToConfiguredDefaultRealmWhenUserRealmCannotBeResolved() throws Exception {
        TestMigrationService migrationService = new TestMigrationService();
        MigrationCheckRequestFilter filter = buildFilter(
            identity("unknown-user@example.com"),
            testEnvConfig(),
            new TestCredentialRepo(null, null),
            migrationService,
            null
        );

        filter.filter(new StubRequestContext("/api/orders", "GET"));

        assertEquals(List.of("configured-default-realm", "system-com"), migrationService.checkedRealms);
    }

    private static MigrationCheckRequestFilter buildFilter(
        SecurityIdentity identity,
        EnvConfigUtils envConfigUtils,
        CredentialRepo credentialRepo,
        MigrationService migrationService,
        JsonWebToken jwt
    ) {
        MigrationCheckRequestFilter filter = new MigrationCheckRequestFilter();
        filter.identity = identity;
        filter.envConfigUtils = envConfigUtils;
        filter.credentialRepo = credentialRepo;
        filter.migrationService = migrationService;
        filter.jwt = jwt;
        return filter;
    }

    private static SecurityIdentity identity(String principalName) {
        return new TestSecurityIdentity(principalName);
    }

    private static EnvConfigUtils testEnvConfig() {
        return new TestEnvConfigUtils("configured-default-realm", "system-com");
    }

    static class TestEnvConfigUtils extends EnvConfigUtils {
        TestEnvConfigUtils(String defaultRealm, String systemRealm) {
            this.defaultRealm = defaultRealm;
            this.systemRealm = systemRealm;
        }
    }

    static class TestMigrationService extends MigrationService {
        final java.util.List<String> checkedRealms = new java.util.ArrayList<>();

        @Override
        public void checkInitialized(String realm) {
            checkedRealms.add(realm);
        }
    }

    static class TestSecurityIdentity implements SecurityIdentity {
        private final Principal principal;

        TestSecurityIdentity(String principalName) {
            this.principal = () -> principalName;
        }

        @Override public Principal getPrincipal() { return principal; }
        @Override public boolean isAnonymous() { return false; }
        @Override public Set<String> getRoles() { return Set.of("admin"); }
        @Override public boolean hasRole(String role) { return getRoles().contains(role); }
        @Override public <T extends io.quarkus.security.credential.Credential> T getCredential(Class<T> credentialType) { return null; }
        @Override public Set<io.quarkus.security.credential.Credential> getCredentials() { return Set.of(); }
        @Override public Set<java.security.Permission> getPermissions() { return Set.of(); }
        @Override public Uni<Boolean> checkPermission(java.security.Permission permission) { return Uni.createFrom().item(true); }
        @Override public Map<String, Object> getAttributes() { return Map.of(); }
        @Override public <T> T getAttribute(String name) { return null; }
    }

    static class TestCredentialRepo extends CredentialRepo {
        private final String matchingIdentity;
        private final String defaultRealm;

        TestCredentialRepo(String matchingIdentity, String defaultRealm) {
            this.matchingIdentity = matchingIdentity;
            this.defaultRealm = defaultRealm;
        }

        @Override
        public Optional<CredentialUserIdPassword> findByUserId(String userId, String realmId, boolean ignoreRules) {
            return lookup(userId);
        }

        @Override
        public Optional<CredentialUserIdPassword> findBySubject(String subject, String realmId, boolean ignoreRules) {
            return lookup(subject);
        }

        private Optional<CredentialUserIdPassword> lookup(String identityValue) {
            if (matchingIdentity == null || defaultRealm == null || !matchingIdentity.equals(identityValue)) {
                return Optional.empty();
            }

            CredentialUserIdPassword credential = new CredentialUserIdPassword();
            credential.setUserId(matchingIdentity);
            credential.setSubject(matchingIdentity);
            credential.setDomainContext(DomainContext.builder()
                .tenantId(defaultRealm)
                .defaultRealm(defaultRealm)
                .orgRefName("TEST-ORG")
                .accountId("TEST-ACCOUNT")
                .dataSegment(0)
                .build());
            credential.setLastUpdate(new Date());
            return Optional.of(credential);
        }
    }

    static class StubRequestContext implements ContainerRequestContext {
        private final String method;
        private final UriInfo uriInfo;
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

        StubRequestContext(String path, String method) {
            this.method = method;
            this.uriInfo = new StubUriInfo(path);
        }

        @Override public Object getProperty(String name) { return null; }
        @Override public Collection<String> getPropertyNames() { return List.of(); }
        @Override public void setProperty(String name, Object object) {}
        @Override public void removeProperty(String name) {}
        @Override public UriInfo getUriInfo() { return uriInfo; }
        @Override public void setRequestUri(URI requestUri) {}
        @Override public void setRequestUri(URI baseUri, URI requestUri) {}
        @Override public Request getRequest() { return null; }
        @Override public String getMethod() { return method; }
        @Override public void setMethod(String method) {}
        @Override public MultivaluedMap<String, String> getHeaders() { return headers; }
        @Override public String getHeaderString(String name) { return headers.getFirst(name); }
        @Override public Date getDate() { return null; }
        @Override public Locale getLanguage() { return null; }
        @Override public int getLength() { return 0; }
        @Override public MediaType getMediaType() { return null; }
        @Override public List<MediaType> getAcceptableMediaTypes() { return List.of(); }
        @Override public List<Locale> getAcceptableLanguages() { return List.of(); }
        @Override public Map<String, Cookie> getCookies() { return Map.of(); }
        @Override public boolean hasEntity() { return false; }
        @Override public InputStream getEntityStream() { return InputStream.nullInputStream(); }
        @Override public void setEntityStream(InputStream input) {}
        @Override public jakarta.ws.rs.core.SecurityContext getSecurityContext() { return null; }
        @Override public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) {}
        @Override public void abortWith(Response response) {}
    }

    static class StubUriInfo implements UriInfo {
        private final String rawPath;

        StubUriInfo(String rawPath) {
            this.rawPath = rawPath;
        }

        @Override public String getPath() { return rawPath; }
        @Override public String getPath(boolean decode) { return rawPath; }
        @Override public List<PathSegment> getPathSegments() { return List.of(); }
        @Override public List<PathSegment> getPathSegments(boolean decode) { return List.of(); }
        @Override public URI getRequestUri() { return URI.create(rawPath); }
        @Override public UriBuilder getRequestUriBuilder() { return UriBuilder.fromPath(rawPath); }
        @Override public URI getAbsolutePath() { return URI.create(rawPath); }
        @Override public UriBuilder getAbsolutePathBuilder() { return UriBuilder.fromPath(rawPath); }
        @Override public URI getBaseUri() { return URI.create("/"); }
        @Override public UriBuilder getBaseUriBuilder() { return UriBuilder.fromPath("/"); }
        @Override public MultivaluedMap<String, String> getPathParameters() { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getQueryParameters() { return new MultivaluedHashMap<>(); }
        @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) { return new MultivaluedHashMap<>(); }
        @Override public List<String> getMatchedURIs() { return List.of(); }
        @Override public List<String> getMatchedURIs(boolean decode) { return List.of(); }
        @Override public List<Object> getMatchedResources() { return List.of(); }
        @Override public URI resolve(URI uri) { return uri; }
        @Override public URI relativize(URI uri) { return uri; }
    }
}

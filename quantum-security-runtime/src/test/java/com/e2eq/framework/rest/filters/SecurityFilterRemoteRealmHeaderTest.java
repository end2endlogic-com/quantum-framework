package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.security.principal.PrincipalContextRequest;
import com.e2eq.framework.security.principal.PrincipalFixture;
import com.e2eq.framework.security.principal.PrincipalResolutionError;
import com.e2eq.framework.security.principal.PrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalRequest;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSettings;
import com.e2eq.framework.security.principal.RemotePrincipalSource;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression guard: with {@code quantum.auth.principal-source=remote} an {@code X-Realm} naming a
 * realm other than the caller's own is never left for the resources to read while the principal
 * stays in its home realm.
 *
 * <p>Resources ({@code BaseResource}) open the datastore named by the raw {@code X-Realm}. That is
 * safe in remote mode because the header is part of the question Auth answers
 * ({@link RemotePrincipalRequest#realm()}, also in the request's cache key), and Auth's resolution
 * ({@code POST /v1/auth/principal-context/resolve} runs
 * {@link com.e2eq.framework.security.principal.PrincipalContextResolver#resolve}) either switches
 * the principal into that realm, admitting it with the target realm's data domain, or refuses
 * {@code REALM_NOT_AUTHORIZED}; the refusal fails the request in SecurityFilter before any
 * resource runs. The source here is that resolver over {@link PrincipalFixture}, asked the way
 * Auth's resource asks it, with refusals carried as the generated client carries Auth's. A realm
 * named in another case is covered by {@code SecurityFilterRealmHeaderTest}.</p>
 */
class SecurityFilterRemoteRealmHeaderTest {

    private static final String ISS = "https://auth.example.test";
    private static final String SUB_ALICE = "sub-alice";

    private final PrincipalFixture world = new PrincipalFixture();

    /** A registered realm alice (home acme-D1, authorized acme-D2 and pooled) has no access to. */
    @BeforeEach
    void foreignRealm() {
        world.realm("globex-D1", RealmTenancyMode.SINGLE_TENANT, "globex", "globex-org", "globex-acct");
        world.assign("bob@globex", "globex-D1", List.of("globex-user"));
    }

    @Test
    void anXRealmNamingARealmTheCallerCannotReachIsRefusedBeforeAnyResourceRuns() {
        AuthResolverSource auth = new AuthResolverSource(world);
        MultivaluedMap<String, String> headers = headers("globex-D1");

        RemotePrincipalResolutionException refused = assertThrows(RemotePrincipalResolutionException.class,
                () -> filter(auth, SUB_ALICE).determinePrincipalContext(request(headers)));

        assertEquals(403, refused.getResponse().getStatus());
        assertEquals("REALM_NOT_AUTHORIZED", refused.code());
        assertEquals("globex-D1", auth.only().realm(), "the header is the question Auth answered");
    }

    @Test
    void anXRealmTheCallerMayUseSwitchesThePrincipalIntoThatRealm() {
        AuthResolverSource auth = new AuthResolverSource(world);
        MultivaluedMap<String, String> headers = headers("acme-D2");

        PrincipalContext context = filter(auth, SUB_ALICE).determinePrincipalContext(request(headers));

        // The realm a resource reads (the header) is the realm the principal was admitted in.
        assertEquals("acme-D2", headers.getFirst("X-Realm"));
        assertEquals("acme-D2", context.getDefaultRealm());
        assertEquals("acme-two", context.getDataDomain().getTenantId());
        assertEquals(List.of("d2-user"), List.of(context.getRoles()).stream()
                .filter(role -> role.endsWith("-user")).toList());
    }

    // --- harness ---

    /** Auth's resolve operation over the fixture: identity mode, the request's selections, typed refusals. */
    static final class AuthResolverSource implements RemotePrincipalSource {
        private final PrincipalFixture world;
        final List<RemotePrincipalRequest> requests = new ArrayList<>();

        AuthResolverSource(PrincipalFixture world) {
            this.world = world;
        }

        @Override
        public PrincipalContext resolve(RemotePrincipalRequest request) {
            requests.add(request);
            PrincipalContextRequest.Builder builder = PrincipalContextRequest.identity(request.subject(), null)
                    .applicationId(PrincipalFixture.APP)
                    .realm(request.realm())
                    .tenantId(request.tenantId())
                    .tenantSelectionOptional(request.tenantSelectionOptional());
            if (request.impersonate() != null) {
                builder.impersonate(request.impersonate().subject(), request.impersonate().userId());
            }
            try {
                return world.resolver().resolve(builder.build()).context();
            } catch (PrincipalResolutionException refused) {
                throw RemotePrincipalResolutionException.of(refused.error(), refused.getMessage());
            }
        }

        RemotePrincipalRequest only() {
            assertEquals(1, requests.size());
            return requests.get(0);
        }
    }

    private static SecurityFilter filter(RemotePrincipalSource source, String subject) {
        SecurityFilter filter = new SecurityFilter();
        JsonWebToken token = jwt(Map.of("iss", ISS, "sub", subject));
        filter.jwt = token;
        filter.securityIdentity = QuarkusSecurityIdentity.builder().setPrincipal(token)
                .addRoles(Set.of("token-role")).build();
        filter.remotePrincipalSettings = new RemotePrincipalSettings() {
            @Override public boolean remote() { return true; }
            @Override public String applicationId() { return PrincipalFixture.APP; }
            @Override public RemotePrincipalSource source() { return source; }
        };
        return filter;
    }

    private static MultivaluedMap<String, String> headers(String realm) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.putSingle("Authorization", "Bearer raw-token");
        headers.putSingle("X-Realm", realm);
        return headers;
    }

    /** A verified token from which only iss and sub may be read. */
    private static JsonWebToken jwt(Map<String, Object> claims) {
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

    private static ContainerRequestContext request(MultivaluedMap<String, String> headers) {
        return (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(),
                new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHeaderString": return headers.getFirst(String.valueOf(args[0]));
                        case "getHeaders": return headers;
                        default:
                            if (method.getReturnType().equals(boolean.class)) return false;
                            return null;
                    }
                });
    }
}

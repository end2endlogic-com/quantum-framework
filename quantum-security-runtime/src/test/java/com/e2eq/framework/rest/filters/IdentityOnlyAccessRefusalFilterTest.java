package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.securityrules.DelegatedIdentityHeaders;
import com.e2eq.framework.security.principal.PrincipalFixture;
import com.e2eq.framework.security.principal.RemotePrincipalResolutionException;
import com.e2eq.framework.security.principal.RemotePrincipalSettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused token contract v2 access token is answered with the augmentor's typed status and code
 * before the resource is matched, so a {@code @RolesAllowed} resource does not answer a bare 403.
 * The filter itself never resolves.
 */
class IdentityOnlyAccessRefusalFilterTest {

    private static final Map<String, Object> V2 = Map.of(
            "iss", "https://auth.example.test", "sub", "sub-alice", "userId", "alice@acme", "jti", "8f6c",
            "scope", "authToken", DelegatedIdentityHeaders.TOKEN_TYPE_CLAIM, DelegatedIdentityHeaders.ACCESS_TOKEN_TYPE);

    private final PrincipalFixture world = new PrincipalFixture();
    private final Map<String, Object> routingData = new HashMap<>();

    @Test
    void aResolvableV2TokenPasses() {
        SecurityIdentity augmented = augment(augmentor(), V2);
        assertNull(run(filter(augmented), Map.of()));
    }

    @Test
    void aDisabledCredentialIsAnsweredTypedWithTheAugmentorsRefusal() {
        world.credential("alice@acme").setActiveStatus(ActiveStatus.INACTIVE);
        SecurityIdentity augmented = augment(augmentor(), V2);
        Response response = run(filter(augmented), Map.of());
        assertEquals(401, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("PRINCIPAL_DISABLED"), String.valueOf(response.getEntity()));
    }

    @Test
    void aReceiverOnTheClaimsPathAnswersTokenContractV2Unresolved() {
        RolesAugmentor augmentor = augmentor();
        augmentor.trustTokenClaims = true;
        Response response = run(filter(augment(augmentor, V2)), Map.of());
        assertEquals(401, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("TOKEN_CONTRACT_V2_UNRESOLVED"));
    }

    @Test
    void anUnavailableCredentialStoreIsASanitized503() {
        RolesAugmentor augmentor = augmentor();
        augmentor.principalContextResolver = new com.e2eq.framework.security.principal.PrincipalContextResolver() {
            @Override
            public com.e2eq.framework.security.principal.PrincipalContextResolution resolve(
                    com.e2eq.framework.security.principal.PrincipalContextRequest request) {
                throw new IllegalStateException("mongodb://user:secret@db is down");
            }
        };
        Response response = run(filter(augment(augmentor, V2)), Map.of());
        assertEquals(503, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains(RemotePrincipalResolutionException.UNAVAILABLE));
        assertTrue(!String.valueOf(response.getEntity()).contains("secret"));
    }

    @Test
    void otherTokensRemoteModeAndLazyAuthenticationAreLeftAlone() {
        routingData.put(IdentityOnlyAccessTokens.REFUSAL_KEY,
                RemotePrincipalResolutionException.unavailable("left by another request"));
        assertNull(run(filter(identity(Map.of("sub", "sub-alice", "realm", "acme-D1"))), Map.of()), "a v1 token");
        IdentityOnlyAccessRefusalFilter remote = filter(identity(V2));
        remote.remoteSettings = new RemotePrincipalSettings() {
            @Override public boolean remote() { return true; }
        };
        assertNull(run(remote, Map.of()), "RemotePrincipalRefusalFilter answers in remote mode");
        IdentityOnlyAccessRefusalFilter lazy = filter(identity(V2));
        lazy.proactiveAuthentication = false;
        assertNull(run(lazy, Map.of()));
    }

    // ------------------------------------------------------------------ fixtures

    private RolesAugmentor augmentor() {
        RolesAugmentor augmentor = new RolesAugmentor();
        augmentor.sessionApplicationId = Optional.of(PrincipalFixture.APP);
        augmentor.sessionRealm = Optional.empty();
        augmentor.principalContextResolver = world.resolver();
        augmentor.envConfigUtils = world.env;
        augmentor.httpHeaders = (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(), new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> {
                    throw new ContextNotActiveException();
                });
        return augmentor;
    }

    private SecurityIdentity augment(RolesAugmentor augmentor, Map<String, Object> claims) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(io.quarkus.vertx.http.runtime.security.HttpSecurityUtils.ROUTING_CONTEXT_ATTRIBUTE, routing());
        return augmentor.augment(identity(claims), null, attributes).await().indefinitely();
    }

    private static SecurityIdentity identity(Map<String, Object> claims) {
        return QuarkusSecurityIdentity.builder().setPrincipal(jwt(claims)).build();
    }

    private IdentityOnlyAccessRefusalFilter filter(SecurityIdentity identity) {
        IdentityOnlyAccessRefusalFilter filter = new IdentityOnlyAccessRefusalFilter();
        filter.identity = identity;
        filter.routingContext = routing();
        return filter;
    }

    private RoutingContext routing() {
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> routingData.get(String.valueOf(args[0]));
                    case "put" -> {
                        routingData.put(String.valueOf(args[0]), args[1]);
                        yield proxy;
                    }
                    case "request" -> io.vertx.core.http.HttpServerRequest.class.cast(Proxy.newProxyInstance(
                            io.vertx.core.http.HttpServerRequest.class.getClassLoader(),
                            new Class<?>[]{io.vertx.core.http.HttpServerRequest.class},
                            (p, m, a) -> null));
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Response run(IdentityOnlyAccessRefusalFilter filter, Map<String, String> headers) {
        Response[] aborted = new Response[1];
        UriInfo uri = (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(), new Class<?>[]{UriInfo.class},
                (proxy, method, args) -> "getPath".equals(method.getName()) ? "/api/orders" : null);
        ContainerRequestContext request = (ContainerRequestContext) Proxy.newProxyInstance(
                ContainerRequestContext.class.getClassLoader(), new Class<?>[]{ContainerRequestContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeaderString" -> headers.get(String.valueOf(args[0]));
                    case "getUriInfo" -> uri;
                    case "abortWith" -> {
                        aborted[0] = (Response) args[0];
                        yield null;
                    }
                    default -> method.getReturnType().equals(boolean.class) ? false : null;
                });
        filter.filter(request);
        return aborted[0];
    }

    private static JsonWebToken jwt(Map<String, Object> claims) {
        Map<String, Object> copy = new HashMap<>(claims);
        return (JsonWebToken) Proxy.newProxyInstance(
                JsonWebToken.class.getClassLoader(),
                new Class<?>[]{JsonWebToken.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getClaim" -> copy.get(String.valueOf(args[0]));
                    case "getName" -> copy.get("sub");
                    case "getGroups", "getClaimNames", "getAudience" -> Set.of();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "jwt";
                    default -> null;
                });
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.session.SessionSelectionException;
import com.e2eq.framework.security.session.SessionSelectionPolicy;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.e2eq.framework.security.session.SessionSelectionException.Code.*;

class SessionSelectionCoordinatorTest {
    @Test
    @SuppressWarnings("unchecked")
    void omittedSelectionDoesNotInstantiateInstalledPolicies() {
        var coordinator = new SessionSelectionCoordinator();
        coordinator.policies = mock(Instance.class);
        coordinator.start(null);
        assertFalse(coordinator.enabled());
        verifyNoInteractions(coordinator.policies);
    }

    @Test
    void registryIsExplicitVersionPinnedAndNeverUsesImplicitPriority() {
        var p = policy("workspace", "1");
        assertNull(SessionSelectionCoordinator.selectPolicy(null, null, List.of(p)));
        assertSame(p, SessionSelectionCoordinator.selectPolicy("workspace", "1", List.of(p)));
        check(INCOMPLETE_CONFIGURATION, () -> SessionSelectionCoordinator.selectPolicy("workspace", null, List.of(p)));
        check(INCOMPLETE_CONFIGURATION, () -> SessionSelectionCoordinator.selectPolicy(null, "1", List.of(p)));
        check(MISSING_POLICY, () -> SessionSelectionCoordinator.selectPolicy("missing", "1", List.of(p)));
        check(VERSION_MISMATCH, () -> SessionSelectionCoordinator.selectPolicy("workspace", "2", List.of(p)));
        check(DUPLICATE_POLICY, () -> SessionSelectionCoordinator.selectPolicy("workspace", "1", List.of(p, policy("workspace", "2"))));
    }

    @Test
    void proposalIsReusedOnlyForExactRequestInputsAndNeverForServiceOrAnonymousIdentity() {
        var p = policy("workspace", "1");
        var calls = new AtomicInteger();
        when(p.select(any())).thenAnswer(call -> { calls.incrementAndGet(); return new SessionSelectionPolicy.Selection("chosen"); });
        var coordinator = coordinator(p);
        var token = token();
        var user = QuarkusSecurityIdentity.builder().setPrincipal(token).build();
        var routing = routing();
        assertEquals("chosen", coordinator.realm(routing, user, "requested", "configured"));
        assertEquals("chosen", coordinator.realm(routing, user, "requested", "configured"));
        assertEquals(1, calls.get());
        coordinator.realm(routing, user, "other", "configured");
        coordinator.realm(routing(), user, "requested", "configured");
        assertEquals(3, calls.get());
        when(token.getClaim("token_type")).thenReturn("service");
        assertNull(coordinator.realm(routing, user, null, "configured"));
        var anonymous = QuarkusSecurityIdentity.builder().setAnonymous(true).build();
        assertNull(coordinator.realm(routing, anonymous, null, "configured"));
        assertEquals(3, calls.get());
    }

    @Test
    void invalidOutputAndFailureDoNotFallBackOrPoisonARequest() {
        var p = policy("workspace", "1");
        var coordinator = coordinator(p);
        var user = QuarkusSecurityIdentity.builder().setPrincipal(token()).build();
        var routing = routing();
        when(p.select(any())).thenReturn(null);
        check(INVALID_RESULT, () -> coordinator.realm(routing, user, null, "configured"));
        when(p.select(any())).thenReturn(new SessionSelectionPolicy.Selection(" "));
        check(INVALID_RESULT, () -> coordinator.realm(routing, user, null, "configured"));
        when(p.select(any())).thenThrow(new IllegalArgumentException("private detail"));
        var failure = assertThrows(SessionSelectionException.class, () -> coordinator.realm(routing, user, null, "configured"));
        assertEquals(POLICY_FAILED, failure.getCode());
        assertFalse(failure.getMessage().contains("private detail"));
        doReturn(new SessionSelectionPolicy.Selection(null)).when(p).select(any());
        assertNull(coordinator.realm(routing, user, null, "configured"));
    }

    private static SessionSelectionPolicy policy(String id, String version) {
        var p = mock(SessionSelectionPolicy.class);
        when(p.id()).thenReturn(id);
        when(p.version()).thenReturn(version);
        return p;
    }
    @SuppressWarnings("unchecked")
    private static SessionSelectionCoordinator coordinator(SessionSelectionPolicy policy) {
        var coordinator = new SessionSelectionCoordinator();
        coordinator.policyId = Optional.of(policy.id());
        coordinator.policyVersion = Optional.of(policy.version());
        coordinator.policies = mock(Instance.class);
        when(coordinator.policies.stream()).thenReturn(java.util.stream.Stream.of(policy));
        coordinator.start(null);
        return coordinator;
    }
    private static JsonWebToken token() {
        var token = mock(JsonWebToken.class);
        when(token.getName()).thenReturn("alice");
        when(token.getSubject()).thenReturn("alice");
        return token;
    }
    private static RoutingContext routing() {
        var routing = mock(RoutingContext.class);
        when(routing.data()).thenReturn(new HashMap<>());
        return routing;
    }
    private static void check(SessionSelectionException.Code code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(SessionSelectionException.class, work).getCode());
    }
}

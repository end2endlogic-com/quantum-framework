package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestAuthorizationStateTest {
    @Test
    void onlyExactInputsWithinOneRequestReuseResultsAndReturnedArraysAreDetached() {
        var request = request();
        var calls = new AtomicInteger();
        java.util.function.Supplier<String[]> resolve = () -> { calls.incrementAndGet(); return new String[]{"editor"}; };
        var key = key("issuer", "subject", "user", "realm", "app", "token-role");
        var first = RequestAuthorizationState.resolve(request, key, resolve);
        first[0] = "admin";
        assertArrayEquals(new String[]{"editor"}, RequestAuthorizationState.resolve(request, key, resolve));
        assertEquals(1, calls.get());
        for (var other : List.of(
                key("another", "subject", "user", "realm", "app", "token-role"),
                key("issuer", "another", "user", "realm", "app", "token-role"),
                key("issuer", "subject", "another", "realm", "app", "token-role"),
                key("issuer", "subject", "user", "another", "app", "token-role"),
                key("issuer", "subject", "user", "realm", "another", "token-role"),
                key("issuer", "subject", "user", "realm", "app", "another"))) {
            RequestAuthorizationState.resolve(request, other, resolve);
        }
        assertEquals(7, calls.get());
        RequestAuthorizationState.resolve(request(), key, resolve);
        assertEquals(8, calls.get());
    }

    @Test
    void failureDoesNotPopulateAnAuthorityResult() {
        var request = request();
        var key = key("issuer", "subject", "user", "realm", "app", "role");
        assertThrows(IllegalStateException.class, () -> RequestAuthorizationState.resolve(request, key,
                () -> { throw new IllegalStateException("authority unavailable"); }));
        assertArrayEquals(new String[]{"editor"}, RequestAuthorizationState.resolve(request, key,
                () -> new String[]{"editor"}));
    }

    @Test
    void completedContextIsDetachedAndClearedBeforeAnotherAdmissionAttempt() {
        var principal = new PrincipalContext.Builder().withUserId("user").withSubjectId("effective-subject")
                .withDefaultRealm("realm").withApplicationId("app").withScope("AUTHENTICATED")
                .withRoles(new String[]{"editor"}).withDataDomain(new DataDomain("org", "acct", "tenant", 0, "user"))
                .build();
        var snapshot = ResolvedAuthorizationContext.from("issuer", "authenticated-subject", principal);
        principal.getRoles()[0] = "admin";
        principal.getDataDomain().setTenantId("other-tenant");
        assertEquals(java.util.Set.of("editor"), snapshot.roles());
        assertEquals("tenant", snapshot.domain().tenant());
        assertEquals("authenticated-subject", snapshot.authenticatedSubject());
        assertEquals("effective-subject", snapshot.principalSubject());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.roles().add("admin"));
        var state = RequestAuthorizationState.from(request());
        assertNull(state.completed());
        state.complete(snapshot);
        assertSame(snapshot, state.completed());
        state.beginAdmission();
        assertNull(state.completed());
    }

    private static RequestAuthorizationState.RoleKey key(String issuer, String subject, String user,
                                                          String realm, String application, String role) {
        return new RequestAuthorizationState.RoleKey(issuer, subject, user, realm, application, List.of(role));
    }

    private static RoutingContext request() {
        var request = mock(RoutingContext.class);
        when(request.data()).thenReturn(new HashMap<>());
        return request;
    }
}

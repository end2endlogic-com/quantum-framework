package com.e2eq.framework.model.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive coverage of the application-scoped-auth resolution algorithm
 * (LEGACY / RESOLVED / AMBIGUOUS / DENIED, plus the {@code "*"} guardrail and
 * multi-aud semantics).
 */
class ApplicationAuthorizationResolverTest {

    @Test
    void nullGrant_isLegacy() {
        var r = ApplicationAuthorizationResolver.resolve(null, null, "scheduler");
        assertEquals(ApplicationAuthorizationResolver.Outcome.LEGACY, r.outcome());
        assertNull(r.audiences());
        assertNull(r.activeApplication());
    }

    @Test
    void emptyGrant_isLegacy() {
        var r = ApplicationAuthorizationResolver.resolve(List.of(), null, "scheduler");
        assertEquals(ApplicationAuthorizationResolver.Outcome.LEGACY, r.outcome());
    }

    @Test
    void blankOnlyGrant_isLegacy() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("", "  "), null, null);
        assertEquals(ApplicationAuthorizationResolver.Outcome.LEGACY, r.outcome());
    }

    @Test
    void singleApp_noRequest_assumesIt() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler"), null, null);
        assertTrue(r.resolved());
        assertEquals("scheduler", r.activeApplication());
        assertEquals(List.of("scheduler"), List.copyOf(r.audiences()));
        assertFalse(r.wildcard());
    }

    @Test
    void multipleApps_noRequest_noDefault_isAmbiguous() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di"), null, null);
        assertEquals(ApplicationAuthorizationResolver.Outcome.AMBIGUOUS, r.outcome());
        assertEquals(List.of("scheduler", "di"), r.candidates());
    }

    @Test
    void multipleApps_noRequest_usesDefault_audIsWholeSet() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di"), "di", null);
        assertTrue(r.resolved());
        assertEquals("di", r.activeApplication());
        // multi-aud: aud is the whole authorized set, not just the active app
        assertEquals(List.of("scheduler", "di"), List.copyOf(r.audiences()));
    }

    @Test
    void multipleApps_defaultNotInSet_isAmbiguous() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di"), "ghost", null);
        assertEquals(ApplicationAuthorizationResolver.Outcome.AMBIGUOUS, r.outcome());
    }

    @Test
    void explicitRequest_inSet_resolvesWithWholeSetAsAud() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di", "psa"), "di", "scheduler");
        assertTrue(r.resolved());
        assertEquals("scheduler", r.activeApplication());
        assertEquals(List.of("scheduler", "di", "psa"), List.copyOf(r.audiences()));
    }

    @Test
    void explicitRequest_notInSet_isDenied() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di"), null, "fulfillment");
        assertEquals(ApplicationAuthorizationResolver.Outcome.DENIED, r.outcome());
        assertEquals("fulfillment", r.deniedApplication());
    }

    @Test
    void wildcard_withRequest_scopesToRequestedOnly_andFlagsWildcard() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("*"), null, "anything");
        assertTrue(r.resolved());
        assertTrue(r.wildcard());
        assertEquals("anything", r.activeApplication());
        // cannot enumerate installed apps, so aud is scoped to just the named app
        assertEquals(List.of("anything"), List.copyOf(r.audiences()));
    }

    @Test
    void wildcard_withoutRequest_isAmbiguous() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("*"), null, null);
        assertEquals(ApplicationAuthorizationResolver.Outcome.AMBIGUOUS, r.outcome());
        assertTrue(r.wildcard());
    }

    @Test
    void wildcardPlusConcrete_withRequest_scopesToRequested() {
        // "*" dominates: any requested app is allowed and the token is scoped to it
        var r = ApplicationAuthorizationResolver.resolve(List.of("*", "scheduler"), "scheduler", "di");
        assertTrue(r.resolved());
        assertTrue(r.wildcard());
        assertEquals("di", r.activeApplication());
        assertEquals(List.of("di"), List.copyOf(r.audiences()));
    }

    @Test
    void requestIsTrimmed() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di"), null, "  scheduler  ");
        assertTrue(r.resolved());
        assertEquals("scheduler", r.activeApplication());
    }
}

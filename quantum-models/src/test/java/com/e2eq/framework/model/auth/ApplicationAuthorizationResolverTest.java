package com.e2eq.framework.model.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
    void credentialWildcardResolvesExplicitApplication() {
        var r = ApplicationAuthorizationResolver.resolve(null, "*", null, "system-admin");
        assertEquals(ApplicationAuthorizationResolver.Outcome.RESOLVED, r.outcome());
        assertEquals(Set.of("*"), r.audiences());
        assertEquals("system-admin", r.activeApplication());
        assertTrue(r.wildcard());
    }

    @Test
    void credentialWildcardPattern_noRequest_noDefault_mintsWildcardAudience() {
        // "*" = no application restriction: mint the wildcard audience instead of
        // demanding a selection from an empty candidate list (this layer cannot
        // enumerate installed apps). Downstream admission = own id ∈ aud or "*" ∈ aud.
        var r = ApplicationAuthorizationResolver.resolve(null, "*", null, null);
        assertEquals(ApplicationAuthorizationResolver.Outcome.RESOLVED, r.outcome());
        assertEquals(Set.of("*"), r.audiences());
        assertNull(r.activeApplication());
        assertTrue(r.wildcard());
    }

    @Test
    void concretePattern_noRequest_noDefault_isAmbiguous() {
        // A concrete pattern is a deliberate scoping decision: callers must name an app.
        var r = ApplicationAuthorizationResolver.resolve(null, "helixor-.*", null, null);
        assertEquals(ApplicationAuthorizationResolver.Outcome.AMBIGUOUS, r.outcome());
    }

    @Test
    void credentialRegexAllowsMatchingApplicationAndDeniesOthers() {
        var allowed = ApplicationAuthorizationResolver.resolve(null, "helixor-.*", null, "helixor-di");
        var denied = ApplicationAuthorizationResolver.resolve(null, "helixor-.*", null, "quantum-system");

        assertEquals(ApplicationAuthorizationResolver.Outcome.RESOLVED, allowed.outcome());
        assertEquals(ApplicationAuthorizationResolver.Outcome.DENIED, denied.outcome());
    }

    @Test
    void perRealmGrantTakesPrecedenceOverCredentialPattern() {
        var r = ApplicationAuthorizationResolver.resolve(
                List.of("tenant-console"), "*", null, "system-admin");

        assertEquals(ApplicationAuthorizationResolver.Outcome.DENIED, r.outcome());
    }

    @Test
    void nullGrant_deniesExplicitApplication() {
        var r = ApplicationAuthorizationResolver.resolve(null, null, "scheduler");
        assertEquals(ApplicationAuthorizationResolver.Outcome.DENIED, r.outcome());
        assertNull(r.audiences());
        assertNull(r.activeApplication());
    }

    @Test
    void emptyGrant_deniesExplicitApplication() {
        var r = ApplicationAuthorizationResolver.resolve(List.of(), null, "scheduler");
        assertEquals(ApplicationAuthorizationResolver.Outcome.DENIED, r.outcome());
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
    void wildcard_withRequest_mintsWildcardAudience_azpIsRequested() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("*"), null, "anything");
        assertTrue(r.resolved());
        assertTrue(r.wildcard());
        assertEquals("anything", r.activeApplication());
        // "*" grant = valid for any app; aud carries the literal wildcard
        assertEquals(List.of("*"), List.copyOf(r.audiences()));
    }

    @Test
    void wildcard_withoutRequest_mintsWildcardAudience_noAzp() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("*"), null, null);
        assertTrue(r.resolved());
        assertTrue(r.wildcard());
        assertNull(r.activeApplication());
        assertEquals(List.of("*"), List.copyOf(r.audiences()));
    }

    @Test
    void wildcardPlusConcrete_withRequest_mintsWildcardAudience() {
        // "*" dominates: the token is valid for any app; azp records the entered one
        var r = ApplicationAuthorizationResolver.resolve(List.of("*", "scheduler"), "scheduler", "di");
        assertTrue(r.resolved());
        assertTrue(r.wildcard());
        assertEquals("di", r.activeApplication());
        assertEquals(List.of("*"), List.copyOf(r.audiences()));
    }

    @Test
    void requestIsTrimmed() {
        var r = ApplicationAuthorizationResolver.resolve(List.of("scheduler", "di"), null, "  scheduler  ");
        assertTrue(r.resolved());
        assertEquals("scheduler", r.activeApplication());
    }
}

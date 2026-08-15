package com.e2eq.framework.rest.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageContractsTest {

    @Test
    void endpointIdentityNormalizesFunctionalPartsAndRejectsMalformedValues() {
        UsageEndpointIdentity identity = UsageEndpointIdentity.parse(" SALES : ORDER : listOrders ");

        assertEquals("sales:order:listOrders", identity.canonicalName());
        assertThrows(IllegalArgumentException.class, () -> UsageEndpointIdentity.parse("missing-parts"));
    }

    @Test
    void policyNormalizesSelectorsAndPreservesWildcard() {
        UsagePolicy policy = new UsagePolicy(
                " standard ",
                " 1 ",
                10,
                Duration.ofMinutes(1),
                Set.of("SALES:ORDER:listOrders", UsagePolicy.ALL_ENDPOINTS));

        assertEquals("standard", policy.id());
        assertTrue(policy.endpointSelectors().contains("sales:order:listOrders"));
        assertTrue(policy.appliesTo(new UsageEndpointIdentity("other", "domain", "operation")));
    }

    @Test
    void admissionDecisionRequiresRetryAndTypedStateErrorData() {
        assertThrows(IllegalArgumentException.class, () -> new UsageAdmissionDecision(
                UsageAdmissionDisposition.REJECTED, "policy", "1", 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new UsageAdmissionDecision(
                UsageAdmissionDisposition.ENFORCEMENT_ERROR, null, null, -1, null, null));
    }

    @Test
    void observationValidatesStatusAndByteRanges() {
        UsageAdmissionDecision admitted = UsageAdmissionDecision.bypassed();

        assertThrows(IllegalArgumentException.class, () -> new UsageObservation(
                "endpoint", "tenant", "subject", "GET", 99, Duration.ZERO, -1, -1, admitted));
        assertThrows(IllegalArgumentException.class, () -> new UsageObservation(
                "endpoint", "tenant", "subject", "GET", 200, Duration.ZERO, -2, -1, admitted));
    }
}

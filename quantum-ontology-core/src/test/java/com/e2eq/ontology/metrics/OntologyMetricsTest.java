package com.e2eq.ontology.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OntologyMetricsTest {

    @Test
    void recordsInvocationsAndDerivedAggregates() {
        OntologyMetrics m = new OntologyMetrics();
        m.recordProviderInvocation("P1", 1_000_000L, 5);
        m.recordProviderInvocation("P1", 3_000_000L, 7);

        Map<String, Object> s = m.snapshotFor("P1");
        assertEquals(2L, s.get("invocations"));
        assertEquals(4_000_000L, s.get("totalNanos"));
        assertEquals(2_000_000L, s.get("avgNanos"));
        assertEquals(3_000_000L, s.get("maxNanos"));
        assertEquals(12L, s.get("targetsProduced"));
        assertEquals(0L, s.get("errors"));
    }

    @Test
    void countsAddsRemovesAndErrors() {
        OntologyMetrics m = new OntologyMetrics();
        m.recordEdgesAdded("P1", 4);
        m.recordEdgesRemoved("P1", 2);
        m.recordProviderError("P1");

        Map<String, Object> s = m.snapshotFor("P1");
        assertEquals(4L, s.get("edgesAdded"));
        assertEquals(2L, s.get("edgesRemoved"));
        assertEquals(1L, s.get("errors"));
    }

    @Test
    void tracksGuardTripsAndStalenessPerKey() {
        OntologyMetrics m = new OntologyMetrics();
        m.recordGuardTrip("P1", "maxDepth");
        m.recordGuardTrip("P1", "maxDepth");
        m.recordGuardTrip("P1", "cycle");
        m.recordStalenessRisk("P1", "Territory");

        @SuppressWarnings("unchecked")
        Map<String, Long> guards = (Map<String, Long>) m.snapshotFor("P1").get("guardTrips");
        assertEquals(2L, guards.get("maxDepth"));
        assertEquals(1L, guards.get("cycle"));

        @SuppressWarnings("unchecked")
        Map<String, Long> stale = (Map<String, Long>) m.snapshotFor("P1").get("stalenessRiskByDependency");
        assertEquals(1L, stale.get("Territory"));
    }

    @Test
    void emptySnapshotForUnknownProvider() {
        OntologyMetrics m = new OntologyMetrics();
        assertTrue(m.snapshotFor("nope").isEmpty());
        assertTrue(m.snapshot().isEmpty());
    }
}

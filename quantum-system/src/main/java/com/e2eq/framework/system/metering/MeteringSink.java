package com.e2eq.framework.system.metering;

/**
 * Where {@link MeteringEvent}s go. The control plane defines the contract; the
 * production implementation publishes to the realm's Redpanda topic and a
 * system-plane consumer projects events into usage rollups / invoice lines
 * (wp1-platform-readiness.md C1/C2).
 *
 * Defining the sink here (and not the transport) keeps emitters decoupled from
 * the eventing module: a caller emits a {@code MeteringEvent} against this
 * interface; whether that becomes a Redpanda record, an in-memory test double,
 * or a no-op in a deployment without metering is the binding's concern.
 */
public interface MeteringSink {

    /**
     * Record a metering event. Implementations must be non-blocking-friendly and
     * must not throw on transient transport failure in a way that breaks the
     * billable action — metering is observational, never on the critical path of
     * the action it measures.
     */
    void record(MeteringEvent event);
}

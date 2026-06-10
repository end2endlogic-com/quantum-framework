package com.e2eq.framework.system.metering;

import java.time.Instant;

/**
 * A single billable/usage observation emitted by a billable action (solver run,
 * entity write, event ingested, GPU-seconds, …). This is the control-plane
 * <em>contract</em> only — the transport (realm-scoped Redpanda topics) and the
 * system-plane projection into usage rollups / invoice lines are implemented in
 * the events work (wp1-platform-readiness.md C1/C2). Metering is deliberately
 * "just another event processor", not a parallel subsystem.
 *
 * The event is realm-scoped (tenantId + realm) so it carries the same isolation
 * and provenance guarantees as every other event; {@code correlationId} ties it
 * back to the originating request/decision chain.
 *
 * @param tenantId      owning tenant
 * @param realm         owning realm (data domain)
 * @param meter         the metered dimension, e.g. {@code solver.run},
 *                      {@code entity.write}, {@code event.ingested}
 * @param quantity      amount metered in the meter's natural unit
 * @param unit          unit of {@code quantity}, e.g. {@code count},
 *                      {@code gpu_seconds}, {@code bytes}
 * @param occurredAt    when the metered action occurred (caller-stamped)
 * @param correlationId request/decision-chain correlation id (nullable)
 */
public record MeteringEvent(
        String tenantId,
        String realm,
        String meter,
        double quantity,
        String unit,
        Instant occurredAt,
        String correlationId) {

    public MeteringEvent {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm is required");
        }
        if (meter == null || meter.isBlank()) {
            throw new IllegalArgumentException("meter is required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
    }
}

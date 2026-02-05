package com.e2eq.framework.model.persistent.usage;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Billing and token replenishment interval per tenant.
 * When the current period ends, consumed is reset and the allocation is replenished.
 *
 * @see TenantTokenAllocation
 */
@RegisterForReflection
public enum BillingInterval {

    /** Replenish monthly (e.g. 1st of each month). */
    MONTHLY(ChronoUnit.MONTHS, 1),

    /** Replenish yearly. */
    YEARLY(ChronoUnit.YEARS, 1);

    private final ChronoUnit unit;
    private final long amount;

    BillingInterval(ChronoUnit unit, long amount) {
        this.unit = unit;
        this.amount = amount;
    }

    public ChronoUnit getUnit() {
        return unit;
    }

    public long getAmount() {
        return amount;
    }

    /**
     * Computes the next period start and end from the given current period end.
     * Uses UTC for month/year arithmetic because {@link Instant} does not support
     * {@link ChronoUnit#MONTHS} or {@link ChronoUnit#YEARS}.
     *
     * @param currentPeriodEnd end of the current period (null to use now as start)
     * @return [periodStart, periodEnd] for the next period
     */
    public Instant[] nextPeriod(Instant currentPeriodEnd) {
        if (currentPeriodEnd == null) {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime end = now.plus(amount, unit);
            return new Instant[] { now.toInstant(), end.toInstant() };
        }
        ZonedDateTime zEnd = currentPeriodEnd.atZone(ZoneOffset.UTC);
        ZonedDateTime nextStart = zEnd;
        ZonedDateTime nextEnd = zEnd.plus(amount, unit);
        return new Instant[] { nextStart.toInstant(), nextEnd.toInstant() };
    }
}

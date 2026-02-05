package com.e2eq.framework.metering;

import com.e2eq.framework.model.persistent.usage.BillingInterval;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BillingInterval}.
 */
@DisplayName("BillingInterval unit tests")
class BillingIntervalTest {

    @Test
    @DisplayName("MONTHLY nextPeriod from null returns current instant and +1 month")
    void monthly_nextPeriod_fromNull() {
        Instant[] next = BillingInterval.MONTHLY.nextPeriod(null);
        assertNotNull(next);
        assertEquals(2, next.length);
        assertNotNull(next[0]);
        assertNotNull(next[1]);
        assertTrue(next[1].isAfter(next[0]));
        // Instant does not support ChronoUnit.MONTHS; expect roughly 28–31 days for one month
        long days = ChronoUnit.DAYS.between(next[0], next[1]);
        assertTrue(days >= 28 && days <= 31, "expected ~1 month, got " + days + " days");
    }

    @Test
    @DisplayName("MONTHLY nextPeriod from given end advances by one month")
    void monthly_nextPeriod_fromGivenEnd() {
        Instant end = Instant.parse("2025-02-01T00:00:00Z");
        Instant[] next = BillingInterval.MONTHLY.nextPeriod(end);
        assertEquals(end, next[0]);
        assertEquals(Instant.parse("2025-03-01T00:00:00Z"), next[1]);
    }

    @Test
    @DisplayName("YEARLY nextPeriod from given end advances by one year")
    void yearly_nextPeriod_fromGivenEnd() {
        Instant end = Instant.parse("2025-02-01T00:00:00Z");
        Instant[] next = BillingInterval.YEARLY.nextPeriod(end);
        assertEquals(end, next[0]);
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), next[1]);
    }

    @Test
    @DisplayName("MONTHLY has unit MONTHS and amount 1")
    void monthly_unitAndAmount() {
        assertEquals(ChronoUnit.MONTHS, BillingInterval.MONTHLY.getUnit());
        assertEquals(1, BillingInterval.MONTHLY.getAmount());
    }

    @Test
    @DisplayName("YEARLY has unit YEARS and amount 1")
    void yearly_unitAndAmount() {
        assertEquals(ChronoUnit.YEARS, BillingInterval.YEARLY.getUnit());
        assertEquals(1, BillingInterval.YEARLY.getAmount());
    }
}

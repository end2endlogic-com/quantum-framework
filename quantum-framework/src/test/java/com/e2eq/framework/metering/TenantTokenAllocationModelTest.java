package com.e2eq.framework.metering;

import com.e2eq.framework.model.persistent.usage.BillingInterval;
import com.e2eq.framework.model.persistent.usage.TenantTokenAllocation;
import com.e2eq.framework.model.persistent.usage.TokenType;
import com.e2eq.framework.model.persistent.usage.UsageScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TenantTokenAllocation} model (getRemainingAmount, getNextReplenishmentDisplay).
 */
@DisplayName("TenantTokenAllocation model unit tests")
class TenantTokenAllocationModelTest {

    @Test
    @DisplayName("getRemainingAmount returns allocated minus consumed")
    void getRemainingAmount() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setAllocatedAmount(1000);
        a.setConsumedAmount(300);
        assertEquals(700, a.getRemainingAmount());
    }

    @Test
    @DisplayName("getRemainingAmount returns 0 when consumed exceeds allocated")
    void getRemainingAmount_noNegative() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setAllocatedAmount(100);
        a.setConsumedAmount(150);
        assertEquals(0, a.getRemainingAmount());
    }

    @Test
    @DisplayName("getNextReplenishmentDisplay returns null when periodEnd null")
    void getNextReplenishmentDisplay_nullPeriodEnd() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setPeriodEnd(null);
        assertNull(a.getNextReplenishmentDisplay());
    }

    @Test
    @DisplayName("getNextReplenishmentDisplay returns ISO date when periodEnd set")
    void getNextReplenishmentDisplay_withPeriodEnd() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setPeriodEnd(Instant.parse("2025-03-01T00:00:00Z"));
        String display = a.getNextReplenishmentDisplay();
        assertNotNull(display);
        assertTrue(display.contains("2025") && display.contains("03") && display.contains("01"));
    }

    @Test
    @DisplayName("setters and getters for billing interval and replenishment amount")
    void billingAndReplenishmentFields() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setBillingInterval(BillingInterval.MONTHLY);
        a.setReplenishmentAmount(5000);
        assertEquals(BillingInterval.MONTHLY, a.getBillingInterval());
        assertEquals(5000, a.getReplenishmentAmount());
    }

    @Test
    @DisplayName("scope and token type stored correctly")
    void scopeAndTokenType() {
        UsageScope scope = new UsageScope(List.of("integration/query/find"), List.of("query_find"), List.of());
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setTokenType(TokenType.API_CALL);
        a.setScope(scope);
        assertEquals(TokenType.API_CALL, a.getTokenType());
        assertTrue(a.getScope().matchesApi("integration", "query", "find"));
    }
}

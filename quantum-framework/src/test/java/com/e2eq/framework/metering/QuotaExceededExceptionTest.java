package com.e2eq.framework.metering;

import com.e2eq.framework.metering.UsageMeteringService;
import com.e2eq.framework.model.persistent.usage.TenantTokenAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UsageMeteringService.QuotaExceededException}.
 */
@DisplayName("QuotaExceededException unit tests")
class QuotaExceededExceptionTest {

    @Test
    @DisplayName("forAllocation builds message with allocation name and next replenishment date")
    void forAllocation_includesNameAndNextReplenishment() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setName("Premium API");
        a.setPeriodEnd(Instant.parse("2025-03-01T00:00:00Z"));

        UsageMeteringService.QuotaExceededException ex = UsageMeteringService.QuotaExceededException.forAllocation(a);

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("Premium API"));
        assertTrue(ex.getMessage().contains("2025") && ex.getMessage().contains("03") && ex.getMessage().contains("01"));
        assertTrue(ex.getMessage().contains("blocked"));
        assertSame(a, ex.getAllocation());
    }

    @Test
    @DisplayName("forAllocation when no replenishment configured mentions no replenishment")
    void forAllocation_noReplenishment() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setName("Fixed Pool");
        a.setPeriodEnd(null);

        UsageMeteringService.QuotaExceededException ex = UsageMeteringService.QuotaExceededException.forAllocation(a);

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("Fixed Pool"));
        assertTrue(ex.getMessage().toLowerCase().contains("no replenishment"));
        assertSame(a, ex.getAllocation());
    }
}

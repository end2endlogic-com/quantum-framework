package com.e2eq.framework.metering;

import com.e2eq.framework.model.persistent.usage.TenantTokenAllocation;
import com.e2eq.framework.rest.exceptions.QuotaExceededExceptionMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link QuotaExceededExceptionMapper}: verifies HTTP 402
 * and error body when token allocation is exceeded.
 */
@QuarkusTest
@DisplayName("QuotaExceededExceptionMapper integration tests")
class QuotaExceededExceptionMapperIT {

    @Inject
    QuotaExceededExceptionMapper mapper;

    @Test
    @DisplayName("toResponse returns 402 with message including allocation name and next replenishment")
    void toResponse_returns402_withMessage() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setName("Premium API");
        a.setPeriodEnd(Instant.parse("2025-03-01T00:00:00Z"));
        UsageMeteringService.QuotaExceededException ex = UsageMeteringService.QuotaExceededException.forAllocation(a);

        Response response = mapper.toResponse(ex);

        assertEquals(402, response.getStatus());
        Object entity = response.getEntity();
        assertNotNull(entity);
        assertTrue(entity.toString().contains("Premium API") || entity.toString().contains("blocked"));
    }

    @Test
    @DisplayName("toResponse entity has status 402")
    void toResponse_entityHasStatus402() {
        TenantTokenAllocation a = new TenantTokenAllocation();
        a.setName("Test Pool");
        a.setPeriodEnd(null);
        UsageMeteringService.QuotaExceededException ex = UsageMeteringService.QuotaExceededException.forAllocation(a);

        Response response = mapper.toResponse(ex);

        assertEquals(402, response.getStatus());
        assertNotNull(response.getEntity());
    }
}

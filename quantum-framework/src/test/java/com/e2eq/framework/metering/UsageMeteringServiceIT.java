package com.e2eq.framework.metering;

import com.e2eq.framework.model.persistent.morphia.usage.ApiCallUsageRecordRepo;
import com.e2eq.framework.model.persistent.morphia.usage.LlmUsageRecordRepo;
import com.e2eq.framework.model.persistent.morphia.usage.TenantTokenAllocationRepo;
import com.e2eq.framework.model.persistent.usage.ApiCallUsageRecord;
import com.e2eq.framework.model.persistent.usage.BillingInterval;
import com.e2eq.framework.model.persistent.usage.LlmUsageRecord;
import com.e2eq.framework.model.persistent.usage.TenantTokenAllocation;
import com.e2eq.framework.model.persistent.usage.TokenType;
import com.e2eq.framework.model.persistent.usage.UsageScope;
import com.e2eq.framework.persistent.BaseRepoTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link com.e2eq.framework.metering.UsageMeteringService},
 * token allocation repos, replenishment, and quota blocking.
 */
@DisplayName("Usage metering integration tests")
class UsageMeteringServiceIT extends BaseRepoTest {

    private static final String REALM = "test-quantum-com";

    @Inject
    UsageMeteringService usageMeteringService;

    @Inject
    TenantTokenAllocationRepo tenantTokenAllocationRepo;

    @Inject
    ApiCallUsageRecordRepo apiCallUsageRecordRepo;

    @Inject
    LlmUsageRecordRepo llmUsageRecordRepo;

    @Test
    @DisplayName("recordApiCall when realm null returns null")
    void recordApiCall_realmNull_returnsNull() {
        ApiCallUsageRecord result = usageMeteringService.recordApiCall(
            null, "user1", "integration", "query", "find", "/api/query/find");
        assertNull(result);
    }

    @Test
    @DisplayName("recordApiCall with no matching allocation saves record without allocationId")
    void recordApiCall_noMatchingAllocation_savesRecord() {
        ApiCallUsageRecord result = usageMeteringService.recordApiCall(
            REALM, "user1", "integration", "query", "find", null);
        assertNotNull(result);
        assertEquals(REALM, result.getRealm());
        assertEquals("user1", result.getCallerUserId());
        assertEquals("integration", result.getArea());
        assertEquals("query", result.getFunctionalDomain());
        assertEquals("find", result.getAction());
        assertNull(result.getAllocationId());
    }

    @Test
    @DisplayName("recordApiCall with matching allocation debits and saves record with allocationId")
    void recordApiCall_matchingAllocation_debitsAndSavesRecord() {
        TenantTokenAllocation allocation = new TenantTokenAllocation();
        allocation.setRealm(REALM);
        allocation.setName("API Pool");
        allocation.setTokenType(TokenType.API_CALL);
        allocation.setScope(new UsageScope(List.of("integration/query/find"), List.of(), List.of()));
        allocation.setAllocatedAmount(100);
        allocation.setConsumedAmount(0);
        allocation.setReplenishmentAmount(100);
        allocation.setBillingInterval(BillingInterval.MONTHLY);
        allocation.setPeriodStart(Instant.now());
        allocation.setPeriodEnd(Instant.now().plusSeconds(3600 * 24 * 31));
        tenantTokenAllocationRepo.save(REALM, allocation);

        ApiCallUsageRecord result = usageMeteringService.recordApiCall(
            REALM, "user1", "integration", "query", "find", null);
        assertNotNull(result);
        assertNotNull(result.getAllocationId());
        assertEquals(allocation.getId(), result.getAllocationId());

        TenantTokenAllocation updated = tenantTokenAllocationRepo.findById(REALM, allocation.getId()).orElseThrow();
        assertEquals(1, updated.getConsumedAmount());
    }

    @Test
    @DisplayName("recordApiCall when allocation exhausted throws QuotaExceededException")
    void recordApiCall_allocationExhausted_throwsQuotaExceeded() {
        TenantTokenAllocation allocation = new TenantTokenAllocation();
        allocation.setRealm(REALM);
        allocation.setName("Exhausted Pool");
        allocation.setTokenType(TokenType.API_CALL);
        allocation.setScope(new UsageScope(List.of("integration/query/find"), List.of(), List.of()));
        allocation.setAllocatedAmount(1);
        allocation.setConsumedAmount(1);
        allocation.setPeriodStart(Instant.now());
        allocation.setPeriodEnd(Instant.now().plusSeconds(3600));
        tenantTokenAllocationRepo.save(REALM, allocation);

        assertThrows(UsageMeteringService.QuotaExceededException.class, () ->
            usageMeteringService.recordApiCall(REALM, "user1", "integration", "query", "find", null));
    }

    @Test
    @DisplayName("recordLlmUsage with matching allocation debits and saves record")
    void recordLlmUsage_matchingAllocation_debitsAndSavesRecord() {
        TenantTokenAllocation allocation = new TenantTokenAllocation();
        allocation.setRealm(REALM);
        allocation.setName("LLM Pool");
        allocation.setTokenType(TokenType.LLM_REQUEST);
        allocation.setScope(new UsageScope(List.of(), List.of("query_find"), List.of()));
        allocation.setAllocatedAmount(50);
        allocation.setConsumedAmount(0);
        allocation.setPeriodStart(Instant.now());
        allocation.setPeriodEnd(Instant.now().plusSeconds(3600 * 24 * 31));
        tenantTokenAllocationRepo.save(REALM, allocation);

        LlmUsageRecord result = usageMeteringService.recordLlmUsage(
            REALM, "runAsUser", "query_find", null, null, null);
        assertNotNull(result);
        assertNotNull(result.getAllocationId());
        assertEquals(allocation.getId(), result.getAllocationId());
        assertEquals("runAsUser", result.getRunAsUserId());
        assertEquals("query_find", result.getToolName());

        TenantTokenAllocation updated = tenantTokenAllocationRepo.findById(REALM, allocation.getId()).orElseThrow();
        assertEquals(1, updated.getConsumedAmount());
    }

    @Test
    @DisplayName("replenishIfPeriodEnded advances period and resets consumed")
    void replenishIfPeriodEnded_advancesPeriodAndResetsConsumed() {
        Instant pastEnd = Instant.now().minusSeconds(3600);
        TenantTokenAllocation allocation = new TenantTokenAllocation();
        allocation.setRealm(REALM);
        allocation.setName("Replenish Pool");
        allocation.setTokenType(TokenType.API_CALL);
        allocation.setScope(new UsageScope(List.of("integration/query/save"), List.of(), List.of()));
        allocation.setAllocatedAmount(100);
        allocation.setConsumedAmount(80);
        allocation.setReplenishmentAmount(100);
        allocation.setBillingInterval(BillingInterval.MONTHLY);
        allocation.setPeriodStart(pastEnd.minusSeconds(3600 * 24 * 30));
        allocation.setPeriodEnd(pastEnd);
        tenantTokenAllocationRepo.save(REALM, allocation);

        TenantTokenAllocation replenished = tenantTokenAllocationRepo.replenishIfPeriodEnded(
            REALM, allocation, Instant.now());

        assertEquals(0, replenished.getConsumedAmount());
        assertEquals(100, replenished.getAllocatedAmount());
        assertTrue(replenished.getPeriodEnd().isAfter(Instant.now()));
    }

    @Test
    @DisplayName("findMatchingForApi returns allocation after replenishment when period ended")
    void findMatchingForApi_replenishesWhenPeriodEnded() {
        Instant pastEnd = Instant.now().minusSeconds(1);
        TenantTokenAllocation allocation = new TenantTokenAllocation();
        allocation.setRealm(REALM);
        allocation.setName("Expired Pool");
        allocation.setTokenType(TokenType.API_CALL);
        allocation.setScope(new UsageScope(List.of("integration/query/plan"), List.of(), List.of()));
        allocation.setAllocatedAmount(10);
        allocation.setConsumedAmount(10);
        allocation.setReplenishmentAmount(10);
        allocation.setBillingInterval(BillingInterval.MONTHLY);
        allocation.setPeriodStart(pastEnd.minusSeconds(3600 * 24 * 30));
        allocation.setPeriodEnd(pastEnd);
        tenantTokenAllocationRepo.save(REALM, allocation);

        var match = tenantTokenAllocationRepo.findMatchingForApi(REALM, "integration", "query", "plan");
        assertTrue(match.isPresent());
        TenantTokenAllocation a = match.get();
        assertEquals(0, a.getConsumedAmount());
        assertTrue(a.getPeriodEnd().isAfter(Instant.now()));
    }
}

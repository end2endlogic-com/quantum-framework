package com.e2eq.framework.metering;

import com.e2eq.framework.model.persistent.morphia.usage.ApiCallUsageRecordRepo;
import com.e2eq.framework.model.persistent.morphia.usage.LlmUsageRecordRepo;
import com.e2eq.framework.model.persistent.morphia.usage.TenantTokenAllocationRepo;
import com.e2eq.framework.model.persistent.usage.ApiCallUsageRecord;
import com.e2eq.framework.model.persistent.usage.LlmUsageRecord;
import com.e2eq.framework.model.persistent.usage.TenantTokenAllocation;
import com.e2eq.framework.model.persistent.usage.TokenType;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * Records API and LLM usage for metering and billing, and consumes from matching
 * {@link com.e2eq.framework.model.persistent.usage.TenantTokenAllocation} when configured.
 * Supports token allocation per tenant scoped to different API sets and Tools/LLM configurations.
 *
 * @see TenantTokenAllocation
 * @see ApiCallUsageRecord
 * @see LlmUsageRecord
 */
@ApplicationScoped
public class UsageMeteringService {

    @Inject
    ApiCallUsageRecordRepo apiCallUsageRecordRepo;

    @Inject
    LlmUsageRecordRepo llmUsageRecordRepo;

    @Inject
    TenantTokenAllocationRepo tenantTokenAllocationRepo;

    /** Token metering is opt-in: set to true in application.properties to enable. When false, no usage is recorded and no quotas apply. */
    @ConfigProperty(name = "quantum.metering.enabled", defaultValue = "false")
    boolean meteringEnabled;

    /** When true and metering is enabled, APIs/tools covered by an allocation are blocked when tokens run out. */
    @ConfigProperty(name = "quantum.metering.enforce-quota", defaultValue = "false")
    boolean enforceQuota;

    /**
     * Records one API call and optionally consumes from a matching API_CALL allocation.
     *
     * @param realm          tenant realm
     * @param callerUserId   principal user id
     * @param area           API area (e.g. integration)
     * @param functionalDomain API domain (e.g. query)
     * @param action         API action (e.g. find, save)
     * @param path           optional request path
     * @return the saved usage record, or null if metering disabled
     */
    public ApiCallUsageRecord recordApiCall(String realm, String callerUserId, String area,
                                            String functionalDomain, String action, String path) {
        if (!meteringEnabled || realm == null) return null;
        ApiCallUsageRecord record = new ApiCallUsageRecord();
        record.setRealm(realm);
        record.setCallerUserId(callerUserId != null ? callerUserId : "");
        record.setArea(area);
        record.setFunctionalDomain(functionalDomain);
        record.setAction(action);
        record.setPath(path);
        record.setAt(Instant.now());

        try {
            var allocation = tenantTokenAllocationRepo.findMatchingForApi(realm, area, functionalDomain, action);
            if (allocation.isPresent()) {
                TenantTokenAllocation a = allocation.get();
                if (enforceQuota && a.getRemainingAmount() < 1) {
                    Log.warnf("Quota exceeded for realm=%s allocation=%s", realm, a.getId());
                    throw QuotaExceededException.forAllocation(a);
                }
                record.setAllocationId(a.getId());
                tenantTokenAllocationRepo.incrementConsumed(realm, a.getId(), 1);
            }
            return apiCallUsageRecordRepo.save(realm, record);
        } catch (QuotaExceededException e) {
            throw e;
        } catch (Exception e) {
            Log.warnf(e, "Metering record failed for API call realm=%s", realm);
            return null;
        }
    }

    /**
     * Records one LLM/agent tool usage and optionally consumes from matching LLM allocations.
     * Debits LLM_REQUEST (1 unit) and, if token counts provided, LLM_INPUT_TOKENS and LLM_OUTPUT_TOKENS
     * from matching allocations.
     *
     * @param realm        tenant realm
     * @param runAsUserId  effective user (runAs) or caller
     * @param toolName     tool name (e.g. query_find, query_save)
     * @param llmConfigKey optional LLM config key
     * @param inputTokens  optional input token count
     * @param outputTokens optional output token count
     * @return the saved usage record, or null if metering disabled
     */
    public LlmUsageRecord recordLlmUsage(String realm, String runAsUserId, String toolName,
                                         String llmConfigKey, Integer inputTokens, Integer outputTokens) {
        if (!meteringEnabled || realm == null) return null;
        LlmUsageRecord record = new LlmUsageRecord();
        record.setRealm(realm);
        record.setRunAsUserId(runAsUserId != null ? runAsUserId : "");
        record.setToolName(toolName);
        record.setLlmConfigKey(llmConfigKey);
        record.setInputTokens(inputTokens);
        record.setOutputTokens(outputTokens);
        record.setAt(Instant.now());

        try {
            var allocation = tenantTokenAllocationRepo.findMatchingForTool(realm, toolName, llmConfigKey);
            if (allocation.isPresent()) {
                TenantTokenAllocation a = allocation.get();
                long debit = 0;
                if (a.getTokenType() == TokenType.LLM_REQUEST) {
                    debit = 1;
                } else if (a.getTokenType() == TokenType.LLM_INPUT_TOKENS && inputTokens != null) {
                    debit = inputTokens;
                } else if (a.getTokenType() == TokenType.LLM_OUTPUT_TOKENS && outputTokens != null) {
                    debit = outputTokens;
                }
                if (debit > 0) {
                    if (enforceQuota && a.getRemainingAmount() < debit) {
                        Log.warnf("Quota exceeded for realm=%s allocation=%s", realm, a.getId());
                        throw QuotaExceededException.forAllocation(a);
                    }
                    record.setAllocationId(a.getId());
                    tenantTokenAllocationRepo.incrementConsumed(realm, a.getId(), debit);
                }
            }
            return llmUsageRecordRepo.save(realm, record);
        } catch (QuotaExceededException e) {
            throw e;
        } catch (Exception e) {
            Log.warnf(e, "Metering record failed for LLM usage realm=%s", realm);
            return null;
        }
    }

    /**
     * Thrown when tokens have run out and quantum.metering.enforce-quota is true.
     * Associated APIs/tools are blocked until the next replenishment. The message includes
     * the allocation name and next replenishment date when available.
     */
    public static class QuotaExceededException extends RuntimeException {

        private final TenantTokenAllocation allocation;

        public QuotaExceededException(String message, TenantTokenAllocation allocation) {
            super(message);
            this.allocation = allocation;
        }

        public static QuotaExceededException forAllocation(TenantTokenAllocation a) {
            String next = a.getNextReplenishmentDisplay();
            String message = next != null
                ? String.format("Usage limit reached for \"%s\". The associated APIs or tools are blocked until the next replenishment on %s.", a.getName(), next)
                : String.format("Usage limit reached for \"%s\". The associated APIs or tools are blocked. No replenishment is configured.", a.getName());
            return new QuotaExceededException(message, a);
        }

        public TenantTokenAllocation getAllocation() {
            return allocation;
        }
    }
}

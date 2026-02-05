package com.e2eq.framework.model.persistent.usage;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Per-tenant allocation of a token type, scoped to specific APIs and/or Tools/LLM configurations.
 * Consumption is tracked; when tokens run out the associated APIs/tools are blocked (when enforce-quota is true).
 * Billing interval and replenishment frequency/amount are configurable per tenant.
 *
 * @see TokenType
 * @see UsageScope
 * @see BillingInterval
 * @see ApiCallUsageRecord
 * @see LlmUsageRecord
 */
@RegisterForReflection
@Entity(value = "tenant_token_allocations", useDiscriminator = false)
public class TenantTokenAllocation {

    @Id
    private ObjectId id;

    /** Realm (tenant) this allocation belongs to. */
    @Indexed
    private String realm;

    /** Display name for this pool (e.g. "Premium API", "LLM Standard"). */
    private String name;

    /** Kind of token (API call, LLM request, input/output tokens). */
    @Indexed
    private TokenType tokenType;

    /** Which APIs, tools, or LLM configs this allocation applies to. */
    private UsageScope scope;

    /** Allocated amount for the current period (e.g. 10_000 API calls). Reset to replenishmentAmount on replenish. */
    private long allocatedAmount;

    /** Amount to add each replenishment (e.g. 10_000). When 0, allocatedAmount is unchanged at replenish. */
    private long replenishmentAmount;

    /** Billing/replenishment interval (MONTHLY, YEARLY). When null, no automatic replenishment. */
    private BillingInterval billingInterval;

    /** Consumed amount in the current period. */
    private long consumedAmount;

    /** Start of the current allocation period (e.g. start of month). */
    @Indexed
    private Instant periodStart;

    /** End of the current allocation period; when passed, allocation is replenished. */
    private Instant periodEnd;

    /** Optional LLM config key this allocation is tied to (for tool/LLM-scoped pools). */
    private String llmConfigKey;

    public TenantTokenAllocation() {
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TokenType getTokenType() {
        return tokenType;
    }

    public void setTokenType(TokenType tokenType) {
        this.tokenType = tokenType;
    }

    public UsageScope getScope() {
        return scope;
    }

    public void setScope(UsageScope scope) {
        this.scope = scope;
    }

    public long getAllocatedAmount() {
        return allocatedAmount;
    }

    public void setAllocatedAmount(long allocatedAmount) {
        this.allocatedAmount = allocatedAmount;
    }

    public long getReplenishmentAmount() {
        return replenishmentAmount;
    }

    public void setReplenishmentAmount(long replenishmentAmount) {
        this.replenishmentAmount = replenishmentAmount;
    }

    public BillingInterval getBillingInterval() {
        return billingInterval;
    }

    public void setBillingInterval(BillingInterval billingInterval) {
        this.billingInterval = billingInterval;
    }

    public long getConsumedAmount() {
        return consumedAmount;
    }

    public void setConsumedAmount(long consumedAmount) {
        this.consumedAmount = consumedAmount;
    }

    public Instant getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(Instant periodStart) {
        this.periodStart = periodStart;
    }

    public Instant getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(Instant periodEnd) {
        this.periodEnd = periodEnd;
    }

    public String getLlmConfigKey() {
        return llmConfigKey;
    }

    public void setLlmConfigKey(String llmConfigKey) {
        this.llmConfigKey = llmConfigKey;
    }

    /** Remaining amount in the current period. */
    public long getRemainingAmount() {
        long rem = allocatedAmount - consumedAmount;
        return Math.max(0, rem);
    }

    /** Human-readable next replenishment time for error messages (e.g. "2025-03-01"). */
    public String getNextReplenishmentDisplay() {
        if (periodEnd == null) return null;
        return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(periodEnd);
    }
}

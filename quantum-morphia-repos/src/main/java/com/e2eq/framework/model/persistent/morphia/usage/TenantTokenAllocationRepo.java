package com.e2eq.framework.model.persistent.morphia.usage;

import com.e2eq.framework.model.persistent.usage.BillingInterval;
import com.e2eq.framework.model.persistent.usage.TenantTokenAllocation;
import com.e2eq.framework.model.persistent.usage.TokenType;
import com.e2eq.framework.model.persistent.usage.UsageScope;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for per-tenant token allocations. Allocations are stored in the tenant's realm database.
 *
 * @see TenantTokenAllocation
 * @see UsageScope
 */
@ApplicationScoped
public class TenantTokenAllocationRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    public TenantTokenAllocation save(String realm, TenantTokenAllocation allocation) {
        if (allocation.getRealm() == null) allocation.setRealm(realm);
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(allocation);
    }

    public Optional<TenantTokenAllocation> findById(String realm, ObjectId id) {
        TenantTokenAllocation a = morphiaDataStoreWrapper.getDataStore(realm)
            .find(TenantTokenAllocation.class)
            .filter(Filters.eq("_id", id))
            .first();
        return Optional.ofNullable(a);
    }

    public List<TenantTokenAllocation> findByRealm(String realm) {
        return morphiaDataStoreWrapper.getDataStore(realm)
            .find(TenantTokenAllocation.class)
            .filter(Filters.eq("realm", realm))
            .iterator()
            .toList();
    }

    public List<TenantTokenAllocation> findByRealmAndTokenType(String realm, TokenType tokenType) {
        return morphiaDataStoreWrapper.getDataStore(realm)
            .find(TenantTokenAllocation.class)
            .filter(Filters.eq("realm", realm), Filters.eq("tokenType", tokenType))
            .iterator()
            .toList();
    }

    /**
     * If the allocation's period has ended and it has a billing interval, replenishes (resets consumed,
     * advances period, optionally sets allocated amount from replenishment amount) and saves.
     *
     * @param realm realm for the tenant
     * @param a     allocation (may be updated and saved)
     * @param now   current time
     * @return the allocation (possibly updated after replenishment)
     */
    public TenantTokenAllocation replenishIfPeriodEnded(String realm, TenantTokenAllocation a, Instant now) {
        if (a.getBillingInterval() == null || a.getPeriodEnd() == null || now.isBefore(a.getPeriodEnd())) {
            return a;
        }
        Instant[] next = a.getBillingInterval().nextPeriod(a.getPeriodEnd());
        a.setConsumedAmount(0);
        a.setPeriodStart(next[0]);
        a.setPeriodEnd(next[1]);
        if (a.getReplenishmentAmount() > 0) {
            a.setAllocatedAmount(a.getReplenishmentAmount());
        }
        return save(realm, a);
    }

    /**
     * Finds an allocation that matches the given API call (area/domain/action) for the realm.
     * Replenishes the allocation if its period has ended. Returns the first matching allocation.
     */
    public Optional<TenantTokenAllocation> findMatchingForApi(String realm, String area, String functionalDomain, String action) {
        List<TenantTokenAllocation> candidates = findByRealmAndTokenType(realm, TokenType.API_CALL);
        Instant now = Instant.now();
        for (TenantTokenAllocation a : candidates) {
            if (a.getScope() != null && a.getScope().matchesApi(area, functionalDomain, action)) {
                a = replenishIfPeriodEnded(realm, a, now);
                if (a.getPeriodEnd() == null || a.getPeriodEnd().isAfter(now))
                    return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds an allocation that matches the given tool name (and optional LLM config key) for the realm.
     * Replenishes the allocation if its period has ended. Returns the first matching allocation.
     */
    public Optional<TenantTokenAllocation> findMatchingForTool(String realm, String toolName, String llmConfigKey) {
        Instant now = Instant.now();
        for (TokenType t : new TokenType[] { TokenType.LLM_REQUEST, TokenType.LLM_INPUT_TOKENS, TokenType.LLM_OUTPUT_TOKENS }) {
            List<TenantTokenAllocation> candidates = findByRealmAndTokenType(realm, t);
            for (TenantTokenAllocation a : candidates) {
                if (a.getScope() != null && a.getScope().matchesTool(toolName) && a.getScope().matchesLlmConfig(llmConfigKey)) {
                    a = replenishIfPeriodEnded(realm, a, now);
                    if (a.getPeriodEnd() == null || a.getPeriodEnd().isAfter(now))
                        return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Increments consumed amount for the allocation and saves. Caller should have verified quota if enforcing.
     */
    public void incrementConsumed(String realm, ObjectId allocationId, long delta) {
        Optional<TenantTokenAllocation> opt = findById(realm, allocationId);
        if (opt.isEmpty()) return;
        TenantTokenAllocation a = opt.get();
        a.setConsumedAmount(a.getConsumedAmount() + delta);
        save(realm, a);
    }
}

package com.e2eq.framework.model.persistent.morphia.usage;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.usage.LlmUsageRecord;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

/**
 * Repository for LLM/agent tool usage records (metering and billing). Records are stored per realm.
 *
 * @see LlmUsageRecord
 */
@ApplicationScoped
public class LlmUsageRecordRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    public LlmUsageRecord save(String realm, LlmUsageRecord record) {
        if (record.getRealm() == null) record.setRealm(realm);
        if (record.getAt() == null) record.setAt(Instant.now());
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(record);
    }

    public List<LlmUsageRecord> findByRealmAndTimeRange(String realm, Instant from, Instant to, int limit) {
        FindOptions options = new FindOptions().sort(Sort.descending("at")).limit(limit);
        return morphiaDataStoreWrapper.getDataStore(realm)
            .find(LlmUsageRecord.class)
            .filter(Filters.eq("realm", realm), Filters.gte("at", from), Filters.lte("at", to))
            .iterator(options)
            .toList();
    }

    public List<LlmUsageRecord> findByRealmAndRunAsUserId(String realm, String runAsUserId, Instant from, Instant to, int limit) {
        FindOptions options = new FindOptions().sort(Sort.descending("at")).limit(limit);
        return morphiaDataStoreWrapper.getDataStore(realm)
            .find(LlmUsageRecord.class)
            .filter(Filters.eq("realm", realm), Filters.eq("runAsUserId", runAsUserId),
                Filters.gte("at", from), Filters.lte("at", to))
            .iterator(options)
            .toList();
    }
}

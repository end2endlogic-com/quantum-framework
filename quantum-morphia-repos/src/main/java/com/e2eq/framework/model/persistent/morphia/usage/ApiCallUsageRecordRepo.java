package com.e2eq.framework.model.persistent.morphia.usage;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.usage.ApiCallUsageRecord;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

/**
 * Repository for API call usage records (metering and billing). Records are stored per realm.
 *
 * @see ApiCallUsageRecord
 */
@ApplicationScoped
public class ApiCallUsageRecordRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    public ApiCallUsageRecord save(String realm, ApiCallUsageRecord record) {
        if (record.getRealm() == null) record.setRealm(realm);
        if (record.getAt() == null) record.setAt(Instant.now());
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(record);
    }

    public List<ApiCallUsageRecord> findByRealmAndTimeRange(String realm, Instant from, Instant to, int limit) {
        FindOptions options = new FindOptions().sort(Sort.descending("at")).limit(limit);
        return morphiaDataStoreWrapper.getDataStore(realm)
            .find(ApiCallUsageRecord.class)
            .filter(Filters.eq("realm", realm), Filters.gte("at", from), Filters.lte("at", to))
            .iterator(options)
            .toList();
    }

    public List<ApiCallUsageRecord> findByRealmAndCaller(String realm, String callerUserId, Instant from, Instant to, int limit) {
        FindOptions options = new FindOptions().sort(Sort.descending("at")).limit(limit);
        return morphiaDataStoreWrapper.getDataStore(realm)
            .find(ApiCallUsageRecord.class)
            .filter(Filters.eq("realm", realm), Filters.eq("callerUserId", callerUserId),
                Filters.gte("at", from), Filters.lte("at", to))
            .iterator(options)
            .toList();
    }
}

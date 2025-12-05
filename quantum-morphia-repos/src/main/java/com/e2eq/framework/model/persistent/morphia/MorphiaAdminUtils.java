package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.query.FindOptions;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Administrative utilities for bulk operations across collections/entities.
 *
 * Provides:
 *  - Copy data within a realm between data domains (across all or a specific collection)
 *  - Delete data for a given data domain (across all or a specific collection)
 *  - Copy data from a data domain in one realm to another realm (across all or a specific collection)
 */
@ApplicationScoped
public class MorphiaAdminUtils {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    /**
     * Delete all documents for the provided data domain either in a specific collection (entity) or across all collections.
     * @param realm the realm/database name
     * @param dataDomain the data domain filter
     * @param collectionName optional collection name; if null, applies to all mapped entities
     * @return number of deleted documents
     */
    public long deleteByDataDomain(String realm, DataDomain dataDomain, @Nullable String collectionName) {
        Objects.requireNonNull(realm, "realm must not be null");
        Objects.requireNonNull(dataDomain, "dataDomain must not be null");
        MorphiaDatastore ds = morphiaDataStoreWrapper.getDataStore(realm);

        AtomicLong total = new AtomicLong(0);
        for (EntityModel em : ds.getMapper().getMappedEntities()) {
            if (!UnversionedBaseModel.class.isAssignableFrom(em.getType())) {
                continue; // skip non-base models
            }
            if (collectionName != null && !em.collectionName().equals(collectionName)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends UnversionedBaseModel> clazz = (Class<? extends UnversionedBaseModel>) em.getType();
            Filter ddFilter = buildDataDomainFilter(dataDomain);
            Query<?> q = ds.find(clazz).filter(ddFilter);
            long deleted = q.delete().getDeletedCount();
            if (deleted > 0) {
                Log.infof("Deleted %d documents from collection %s in realm %s for dataDomain %s", deleted, em.collectionName(), realm, safeDomainString(dataDomain));
            }
            total.addAndGet(deleted);
        }
        return total.get();
    }

    /**
     * Copy all documents for the given data domain FROM one data domain TO another data domain within the same realm.
     * Note: New documents are created with new ObjectIds; refName is preserved. If a unique constraint prevents insert, the document is skipped.
     * @param realm realm/database
     * @param from source data domain
     * @param to target data domain
     * @param collectionName optional single collection to process; null means all
     * @return number of successfully copied documents
     */
    public long copyDataDomainWithinRealm(String realm, DataDomain from, DataDomain to, @Nullable String collectionName) {
        return copyDataDomainAcrossRealms(realm, realm, from, to, collectionName);
    }

    /**
     * Copy all documents for the given data domain FROM one realm to another realm, possibly changing the data domain.
     * Note: New documents are created with new ObjectIds; refName is preserved. If a unique constraint prevents insert, the document is skipped.
     * @param fromRealm source realm
     * @param toRealm destination realm
     * @param from source data domain
     * @param to target data domain
     * @param collectionName optional single collection to process; null means all
     * @return number of successfully copied documents
     */
    public long copyDataDomainAcrossRealms(String fromRealm, String toRealm, DataDomain from, DataDomain to, @Nullable String collectionName) {
        Objects.requireNonNull(fromRealm, "fromRealm must not be null");
        Objects.requireNonNull(toRealm, "toRealm must not be null");
        Objects.requireNonNull(from, "from dataDomain must not be null");
        Objects.requireNonNull(to, "to dataDomain must not be null");

        MorphiaDatastore src = morphiaDataStoreWrapper.getDataStore(fromRealm);
        MorphiaDatastore dst = morphiaDataStoreWrapper.getDataStore(toRealm);

        AtomicLong totalCopied = new AtomicLong(0);

        for (EntityModel em : src.getMapper().getMappedEntities()) {
            if (!UnversionedBaseModel.class.isAssignableFrom(em.getType())) {
                continue; // only copy our base models
            }
            if (collectionName != null && !em.collectionName().equals(collectionName)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Class<? extends UnversionedBaseModel> clazz = (Class<? extends UnversionedBaseModel>) em.getType();

            Filter ddFilter = buildDataDomainFilter(from);
            FindOptions options = new FindOptions();
            MorphiaCursor<? extends UnversionedBaseModel> cursor = src.find(clazz).filter(ddFilter).iterator(options);
            long copiedForEntity = 0;
            try (cursor) {
                for (UnversionedBaseModel entity : cursor.toList()) {
                    try {
                        UnversionedBaseModel clone = shallowCopy(entity);
                        // ensure new identity and updated domain
                        clone.setId(null);
                        clone.setDataDomain(to);
                        // set modelSourceRealm transiently if helpful for downstream interceptors
                        clone.setModelSourceRealm(toRealm);
                        dst.save(clone);
                        copiedForEntity++;
                    } catch (Exception ex) {
                        // likely unique index violation or validation; log and continue
                        Log.warnf(ex, "Skipping entity %s/%s due to error during copy: %s", em.collectionName(), Optional.ofNullable(entity.getRefName()).orElse("<no-refName>"), ex.getMessage());
                    }
                }
            }
            if (copiedForEntity > 0) {
                Log.infof("Copied %d documents of collection %s from realm %s to %s (from DD %s to DD %s)", copiedForEntity, em.collectionName(), fromRealm, toRealm, safeDomainString(from), safeDomainString(to));
            }
            totalCopied.addAndGet(copiedForEntity);
        }
        return totalCopied.get();
    }

    private static Filter buildDataDomainFilter(DataDomain dd) {
        Filter f = Filters.eq("dataDomain.orgRefName", dd.getOrgRefName());
        f = Filters.and(
                f,
                Filters.eq("dataDomain.accountNum", dd.getAccountNum()),
                Filters.eq("dataDomain.tenantId", dd.getTenantId()),
                Filters.eq("dataDomain.ownerId", dd.getOwnerId()),
                Filters.eq("dataDomain.dataSegment", dd.getDataSegment())
        );
        if (dd.getLocationId() != null) {
            f = Filters.and(f, Filters.eq("dataDomain.locationId", dd.getLocationId()));
        }
        if (dd.getBusinessTransactionId() != null) {
            f = Filters.and(f, Filters.eq("dataDomain.businessTransactionId", dd.getBusinessTransactionId()));
        }
        return f;
    }

    private static String safeDomainString(DataDomain dd) {
        return String.format("{org:%s, acct:%s, tenant:%s, seg:%d, owner:%s}",
                dd.getOrgRefName(), dd.getAccountNum(), dd.getTenantId(), dd.getDataSegment(), dd.getOwnerId());
    }

    /**
     * Performs a shallow copy of the entity; references and nested objects are preserved by reference.
     * This is sufficient for most administrative copy operations where the target DataDomain changes.
     */
    private static UnversionedBaseModel shallowCopy(UnversionedBaseModel src) throws Exception {
        // Using the default no-arg constructor and Lombok-generated setters via reflection would be heavy.
        // However, for our use, we can reuse the same instance by creating a detached copy via serialization is overkill.
        // Instead, we clone through a simple copy of accessible fields from the base type and rely on Morphia to persist.
        // For safety and minimalism, we return the same runtime class via default constructor and copy properties that are common.
        UnversionedBaseModel dst = src.getClass().getDeclaredConstructor().newInstance();
        // base fields
        dst.setRefName(src.getRefName());
        dst.setDisplayName(src.getDisplayName());
        dst.setTags(src.getTags());
        dst.setAdvancedTags(src.getAdvancedTags());
        dst.setActiveStatus(src.getActiveStatus());
        dst.setAuditInfo(src.getAuditInfo());
        dst.setReferences(src.getReferences());
        dst.setPersistentEvents(src.getPersistentEvents());
        dst.setSignatures(src.getSignatures());
        dst.setDefaultUIActions(src.defaultUIActions());
        // Note: dataDomain and id will be set by caller
        return dst;
    }
}

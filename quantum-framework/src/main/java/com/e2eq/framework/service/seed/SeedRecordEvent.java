package com.e2eq.framework.service.seed;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Event object passed to {@link SeedRecordListener} when seed records are applied.
 *
 * <p>Contains all contextual information about the seed operation including:
 * <ul>
 *   <li>The collection/entity name</li>
 *   <li>The seed pack and version that provided the data</li>
 *   <li>The realm and tenant context</li>
 *   <li>The actual records that were persisted</li>
 *   <li>Whether the operation was an upsert or insert</li>
 * </ul>
 *
 * @see SeedRecordListener
 */
public final class SeedRecordEvent {

    private final String collection;
    private final String seedPack;
    private final String version;
    private final SeedContext context;
    private final List<Map<String, Object>> records;
    private final boolean upsert;

    private SeedRecordEvent(Builder builder) {
        this.collection = Objects.requireNonNull(builder.collection, "collection");
        this.seedPack = builder.seedPack;
        this.version = builder.version;
        this.context = Objects.requireNonNull(builder.context, "context");
        this.records = builder.records != null
                ? Collections.unmodifiableList(builder.records)
                : Collections.emptyList();
        this.upsert = builder.upsert;
    }

    /**
     * Returns the collection/entity name where records were applied.
     *
     * @return the collection name
     */
    public String getCollection() {
        return collection;
    }

    /**
     * Returns the name of the seed pack that provided these records.
     *
     * @return the seed pack name, or null if not available
     */
    public String getSeedPack() {
        return seedPack;
    }

    /**
     * Returns the version of the seed pack.
     *
     * @return the seed pack version, or null if not available
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the seed context with realm and tenant information.
     *
     * @return the seed context
     */
    public SeedContext getContext() {
        return context;
    }

    /**
     * Returns the realm/database where records were applied.
     *
     * @return the realm identifier
     */
    public String getRealm() {
        return context.getRealm();
    }

    /**
     * Returns the records that were applied to the database.
     *
     * <p>These are the final transformed records after all transforms have been applied.
     * The list is unmodifiable.
     *
     * @return unmodifiable list of applied records
     */
    public List<Map<String, Object>> getRecords() {
        return records;
    }

    /**
     * Returns whether the operation was an upsert (true) or insert-only (false).
     *
     * @return true if upsert, false if insert-only
     */
    public boolean isUpsert() {
        return upsert;
    }

    /**
     * Returns the number of records that were applied.
     *
     * @return the record count
     */
    public int getRecordCount() {
        return records.size();
    }

    @Override
    public String toString() {
        return "SeedRecordEvent{" +
                "collection='" + collection + '\'' +
                ", seedPack='" + seedPack + '\'' +
                ", version='" + version + '\'' +
                ", realm='" + getRealm() + '\'' +
                ", recordCount=" + records.size() +
                ", upsert=" + upsert +
                '}';
    }

    /**
     * Creates a new builder for constructing SeedRecordEvent instances.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for SeedRecordEvent.
     */
    public static final class Builder {
        private String collection;
        private String seedPack;
        private String version;
        private SeedContext context;
        private List<Map<String, Object>> records;
        private boolean upsert = true;

        private Builder() {}

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder seedPack(String seedPack) {
            this.seedPack = seedPack;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder context(SeedContext context) {
            this.context = context;
            return this;
        }

        public Builder records(List<Map<String, Object>> records) {
            this.records = records;
            return this;
        }

        public Builder upsert(boolean upsert) {
            this.upsert = upsert;
            return this;
        }

        public SeedRecordEvent build() {
            return new SeedRecordEvent(this);
        }
    }
}

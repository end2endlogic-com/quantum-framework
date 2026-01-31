package com.e2eq.framework.service.seed;

import java.util.List;
import java.util.Map;

/**
 * SPI interface for receiving callbacks when seed records are applied to the database.
 *
 * <p>Implementations of this interface can react to seed data being inserted or updated,
 * enabling application components to synchronize their state with the database content.
 * For example, a JobRunner could implement this interface to register scheduled jobs
 * when job configuration seeds are applied.
 *
 * <p>Listeners are invoked after records have been successfully persisted to the database.
 * They receive the collection name, the applied records, and contextual information about
 * the seed operation.
 *
 * <h2>Usage</h2>
 * <p>Implement this interface and register as a CDI bean (using {@code @ApplicationScoped}
 * or similar) for automatic discovery, or manually register with
 * {@link SeedLoader.Builder#addRecordListener(SeedRecordListener)}.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class JobRunnerSeedListener implements SeedRecordListener {
 *
 *     @Inject
 *     JobScheduler jobScheduler;
 *
 *     @Override
 *     public boolean appliesTo(String collection, SeedContext context) {
 *         return "autoPublishConfig".equals(collection) ||
 *                "scheduledJobs".equals(collection);
 *     }
 *
 *     @Override
 *     public void onRecordsApplied(SeedRecordEvent event) {
 *         for (Map<String, Object> record : event.getRecords()) {
 *             jobScheduler.registerOrUpdate(record);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see SeedLoader
 * @see SeedRecordEvent
 */
public interface SeedRecordListener {

    /**
     * Determines whether this listener should receive callbacks for the given collection.
     *
     * <p>This method is called before records are applied to allow listeners to
     * efficiently filter which collections they care about.
     *
     * @param collection the name of the collection/entity being seeded
     * @param context the seed context with realm and tenant information
     * @return {@code true} if this listener wants to receive callbacks for this collection
     */
    boolean appliesTo(String collection, SeedContext context);

    /**
     * Called after seed records have been successfully applied to the database.
     *
     * <p>This callback is invoked after the batch write completes successfully.
     * Implementations should handle any exceptions internally and log appropriately,
     * as throwing exceptions may interrupt the seeding process.
     *
     * <p>The records in the event are the final transformed records that were
     * persisted to the database.
     *
     * @param event the event containing the applied records and context
     */
    void onRecordsApplied(SeedRecordEvent event);

    /**
     * Returns the priority of this listener. Higher priority listeners are invoked first.
     * Default priority is 0.
     *
     * @return the priority value
     */
    default int priority() {
        return 0;
    }

    /**
     * Indicates whether this listener should be invoked asynchronously.
     *
     * <p>When {@code true}, the listener callback will be executed in a separate thread,
     * allowing the seed operation to continue without waiting. This is useful for
     * listeners that perform expensive operations like network calls or complex
     * synchronization.</p>
     *
     * <p>Default is {@code false} (synchronous execution).</p>
     *
     * @return {@code true} for async execution, {@code false} for sync
     */
    default boolean async() {
        return false;
    }
}

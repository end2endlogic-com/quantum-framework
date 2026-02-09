package com.e2eq.framework.imports.spi;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;

import java.util.Collections;
import java.util.List;

/**
 * SPI for batch-level processing during CSV import.
 * Invoked once per batch before validation and before commit.
 * Receives {@link ImportBatch} - the same abstraction used by the importer.
 *
 * <p>Implementations are discovered via CDI and referenced by name in ImportProfile
 * ({@code batchLifecycleHandlerNames}). Use for:
 * <ul>
 *   <li>Batch API calls (e.g., batch geocode for lat/long augmentation)</li>
 *   <li>Pre-loading lookups for the batch</li>
 *   <li>Cross-row validation or deduplication</li>
 *   <li>Audit logging, cache invalidation (in afterBatch)</li>
 * </ul>
 *
 * <p><b>Example: Batch geocode for locations</b>
 * <pre>{@code
 * @ApplicationScoped
 * public class BatchGeocodeHandler implements BatchLifecycleHandler {
 *     @Inject MapboxBatchGeocodeClient mapboxClient;
 *
 *     @Override
 *     public String getName() { return "batchGeocode"; }
 *
 *     @Override
 *     public BatchResult beforeBatch(ImportBatch<? extends UnversionedBaseModel> batch,
 *                                    ImportContext context) {
 *         List<String> addresses = batch.getBeans().stream()
 *             .filter(b -> b instanceof Location)
 *             .map(b -> ((Location) b).getMailingAddress().getFullAddress())
 *             .toList();
 *         List<LatLong> results = mapboxClient.batchGeocode(addresses);
 *         for (int i = 0; i < batch.size() && i < results.size(); i++) {
 *             if (batch.getBeans().get(i) instanceof Location loc && results.get(i) != null) {
 *                 loc.setLatitude(results.get(i).getLat());
 *                 loc.setLongitude(results.get(i).getLng());
 *             }
 *         }
 *         return BatchResult.success();
 *     }
 *
 *     @Override
 *     public boolean appliesTo(Class<?> targetClass) {
 *         return Location.class.isAssignableFrom(targetClass);
 *     }
 * }
 * }</pre>
 */
public interface BatchLifecycleHandler {

    /**
     * Get the unique name of this handler.
     * Used in ImportProfile.batchLifecycleHandlerNames.
     */
    String getName();

    /**
     * Called before a batch is validated and saved.
     * Use for: batch API calls, pre-loading lookups, cross-row validation,
     * augmenting beans in-place (e.g., lat/long from geocode).
     *
     * @param batch the ImportBatch (beans + row metadata); augment beans in-place
     * @param context import context (realm, session, profile)
     * @return result indicating success, partial failure, or abort
     */
    BatchResult beforeBatch(ImportBatch<? extends UnversionedBaseModel> batch, ImportContext context);

    /**
     * Called after a batch is successfully saved.
     * Use for: audit logging, cache invalidation, notifications.
     *
     * @param batch the ImportBatch that was saved
     * @param context import context
     */
    default void afterBatch(ImportBatch<? extends UnversionedBaseModel> batch, ImportContext context) {
        // No-op by default
    }

    /**
     * Check if this handler applies to the given target class.
     */
    default boolean appliesTo(Class<?> targetClass) {
        return true;
    }

    /**
     * Get the priority order. Lower values run first. Default 100.
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Result of a batch lifecycle operation.
     */
    class BatchResult {
        private final boolean success;
        private final String errorMessage;
        private final List<BatchRowError> rowErrors;

        private BatchResult(boolean success, String errorMessage, List<BatchRowError> rowErrors) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.rowErrors = rowErrors != null ? List.copyOf(rowErrors) : List.of();
        }

        /** Successful - continue with validation and save. */
        public static BatchResult success() {
            return new BatchResult(true, null, List.of());
        }

        /** Abort the entire batch with an error. */
        public static BatchResult error(String message) {
            return new BatchResult(false, message, List.of());
        }

        /** Partial failure - some rows have errors. */
        public static BatchResult partial(List<BatchRowError> errors) {
            if (errors == null || errors.isEmpty()) {
                return success();
            }
            return new BatchResult(false, "Batch has row-level errors", errors);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public List<BatchRowError> getRowErrors() {
            return Collections.unmodifiableList(rowErrors);
        }

        /**
         * Per-row error from a batch handler.
         *
         * @param batchIndex 0-based index into the batch
         * @param field optional field name
         * @param message error message
         */
        public record BatchRowError(int batchIndex, String field, String message) {}
    }
}

package com.e2eq.framework.imports.spi;

import com.e2eq.framework.model.persistent.base.BaseModel;

import java.util.Map;

/**
 * SPI for custom transformation logic during CSV import.
 * Implementations are discovered via CDI and invoked by name in ImportProfile.
 *
 * <p>This runs after field calculators but before bean validation.
 * Use this for complex transformations that can't be expressed via ColumnMapping.</p>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * @ApplicationScoped
 * @Named("addressNormalizer")
 * public class AddressNormalizer implements PreValidationTransformer {
 *     @Override
 *     public String getName() {
 *         return "addressNormalizer";
 *     }
 *
 *     @Override
 *     public TransformResult transform(BaseModel bean, Map<String, Object> rowData, ImportContext context) {
 *         if (bean instanceof ContactInfo contact) {
 *             // Normalize address fields
 *             normalizeAddress(contact);
 *         }
 *         return TransformResult.success();
 *     }
 * }
 * }</pre>
 */
public interface PreValidationTransformer {

    /**
     * Get the unique name of this transformer.
     * This name is used in ImportProfile.preValidationTransformerNames.
     *
     * @return the transformer name
     */
    String getName();

    /**
     * Transform the bean before validation.
     *
     * @param bean the bean being imported
     * @param rowData the raw row data from CSV (column name -> value)
     * @param context the import context with profile and additional metadata
     * @return the transformation result (success, skip, or error)
     */
    TransformResult transform(BaseModel bean, Map<String, Object> rowData, ImportContext context);

    /**
     * Check if this transformer applies to the given bean type.
     * Default returns true (applies to all types).
     *
     * @param beanClass the class being imported
     * @return true if this transformer should run
     */
    default boolean appliesTo(Class<? extends BaseModel> beanClass) {
        return true;
    }

    /**
     * Get the priority order for this transformer.
     * Lower values run first. Default is 100.
     *
     * @return the priority order
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Result of a transformation operation.
     */
    class TransformResult {
        private final boolean success;
        private final boolean skip;
        private final String errorMessage;

        private TransformResult(boolean success, boolean skip, String errorMessage) {
            this.success = success;
            this.skip = skip;
            this.errorMessage = errorMessage;
        }

        /**
         * Create a successful result - continue processing.
         */
        public static TransformResult success() {
            return new TransformResult(true, false, null);
        }

        /**
         * Create a skip result - skip this row without error.
         */
        public static TransformResult skip() {
            return new TransformResult(true, true, null);
        }

        /**
         * Create a skip result with a reason.
         */
        public static TransformResult skip(String reason) {
            return new TransformResult(true, true, reason);
        }

        /**
         * Create an error result - mark row as error.
         */
        public static TransformResult error(String message) {
            return new TransformResult(false, false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isSkip() {
            return skip;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}

package com.e2eq.framework.imports.spi;

import java.util.Map;

/**
 * SPI for resolving field values based on row data during CSV import.
 * Unlike static lookups, this allows arbitrary code to process each row
 * and compute a value dynamically.
 *
 * <p>Implementations are discovered via CDI and invoked by name in ColumnMapping.</p>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * @ApplicationScoped
 * @Named("skuResolver")
 * public class SkuResolver implements RowValueResolver {
 *     @Inject
 *     ProductService productService;
 *
 *     @Override
 *     public String getName() {
 *         return "skuResolver";
 *     }
 *
 *     @Override
 *     public ResolveResult resolve(String inputValue, Map<String, Object> rowData, ImportContext context) {
 *         // Access multiple columns from the row
 *         String category = (String) rowData.get("category");
 *         String vendor = (String) rowData.get("vendor");
 *
 *         // Run arbitrary logic
 *         String resolvedSku = productService.findOrCreateSku(inputValue, category, vendor);
 *
 *         if (resolvedSku != null) {
 *             return ResolveResult.success(resolvedSku);
 *         } else {
 *             return ResolveResult.error("Could not resolve SKU for: " + inputValue);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Configuration in ColumnMapping:</p>
 * <pre>{@code
 * {
 *   "sourceColumn": "product_code",
 *   "targetField": "sku",
 *   "rowValueResolverName": "skuResolver"
 * }
 * }</pre>
 */
public interface RowValueResolver {

    /**
     * Get the unique name of this resolver.
     * This name is used in ColumnMapping.rowValueResolverName.
     *
     * @return the resolver name
     */
    String getName();

    /**
     * Resolve a value based on input and row data.
     *
     * @param inputValue the value from the CSV column (after any prior transformations)
     * @param rowData all column values from the current row (column name to value)
     * @param context the import context with profile, realm, and row metadata
     * @return the resolution result
     */
    ResolveResult resolve(String inputValue, Map<String, Object> rowData, ImportContext context);

    /**
     * Check if this resolver applies to the given target class.
     * Default returns true (applies to all types).
     *
     * @param targetClass the class being imported
     * @return true if this resolver should be available
     */
    default boolean appliesTo(Class<?> targetClass) {
        return true;
    }

    /**
     * Get the priority order for this resolver.
     * Lower values run first when multiple resolvers are configured.
     * Default is 100.
     *
     * @return the priority order
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Result of a value resolution operation.
     */
    class ResolveResult {
        private final boolean success;
        private final Object value;
        private final String errorMessage;
        private final boolean skip;

        private ResolveResult(boolean success, Object value, String errorMessage, boolean skip) {
            this.success = success;
            this.value = value;
            this.errorMessage = errorMessage;
            this.skip = skip;
        }

        /**
         * Create a successful result with the resolved value.
         *
         * @param value the resolved value (can be null)
         * @return success result
         */
        public static ResolveResult success(Object value) {
            return new ResolveResult(true, value, null, false);
        }

        /**
         * Create a result that passes through the original value unchanged.
         *
         * @param originalValue the original input value
         * @return passthrough result
         */
        public static ResolveResult passthrough(Object originalValue) {
            return new ResolveResult(true, originalValue, null, false);
        }

        /**
         * Create a result that returns null for the field.
         *
         * @return null result
         */
        public static ResolveResult nullValue() {
            return new ResolveResult(true, null, null, false);
        }

        /**
         * Create a skip result - skip this row without error.
         *
         * @return skip result
         */
        public static ResolveResult skip() {
            return new ResolveResult(true, null, null, true);
        }

        /**
         * Create a skip result with a reason.
         *
         * @param reason the skip reason
         * @return skip result with reason
         */
        public static ResolveResult skip(String reason) {
            return new ResolveResult(true, null, reason, true);
        }

        /**
         * Create an error result - mark row as error.
         *
         * @param message the error message
         * @return error result
         */
        public static ResolveResult error(String message) {
            return new ResolveResult(false, null, message, false);
        }

        /**
         * Check if resolution was successful.
         *
         * @return true if successful
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Get the resolved value.
         *
         * @return the resolved value, or null
         */
        public Object getValue() {
            return value;
        }

        /**
         * Get the error message if resolution failed.
         *
         * @return the error message, or null if successful
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * Check if this row should be skipped.
         *
         * @return true if row should be skipped
         */
        public boolean isSkip() {
            return skip;
        }
    }
}

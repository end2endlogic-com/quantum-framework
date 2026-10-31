package com.e2eq.framework.imports.spi;

import java.util.Map;

/**
 * SPI for resolving field values based on row data during CSV import.
 * Unlike static lookups, this allows arbitrary code to process each row
 * and compute a value dynamically.
 *
 * <b>Arc CDI Bean Integration</b>
 * <p>Implementations are discovered as Arc CDI beans, which means you can:</p>
 * <ul>
 *   <li>Inject any other CDI beans (services, repositories, etc.)</li>
 *   <li>Use @ConfigProperty for configuration</li>
 *   <li>Access the full Quarkus/Arc ecosystem</li>
 *   <li>Update multiple collections from a single row by injecting multiple repositories</li>
 * </ul>
 *
 * <p>Mark your implementation with {@code @ApplicationScoped} (recommended) or
 * {@code @Dependent} scope. The resolver is discovered via CDI's {@code Instance}
 * mechanism and invoked by name as specified in the ColumnMapping configuration.</p>
 *
 * <b>Basic Example</b>
 * <pre>{@code
 * @ApplicationScoped
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
 *         String category = (String) rowData.get("category");
 *         String vendor = (String) rowData.get("vendor");
 *
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
 * <b>Multi-Collection Update Example</b>
 * <p>Since resolvers are Arc beans, you can inject multiple repositories and update
 * multiple collections from a single CSV row:</p>
 * <pre>{@code
 * @ApplicationScoped
 * public class OrderLineResolver implements RowValueResolver {
 *     @Inject
 *     OrderRepo orderRepo;
 *
 *     @Inject
 *     InventoryRepo inventoryRepo;
 *
 *     @Inject
 *     AuditLogRepo auditLogRepo;
 *
 *     @Override
 *     public String getName() {
 *         return "orderLineResolver";
 *     }
 *
 *     @Override
 *     public ResolveResult resolve(String inputValue, Map<String, Object> rowData, ImportContext context) {
 *         String orderId = (String) rowData.get("order_id");
 *         String productSku = (String) rowData.get("product_sku");
 *         Integer quantity = Integer.parseInt((String) rowData.get("quantity"));
 *
 *         // Update inventory (different collection)
 *         inventoryRepo.decrementStock(productSku, quantity, context.getRealmId());
 *
 *         // Create audit log entry (another collection)
 *         AuditLog log = AuditLog.builder()
 *             .action("IMPORT_ORDER_LINE")
 *             .entityRef(orderId)
 *             .details("Imported line for SKU: " + productSku)
 *             .build();
 *         auditLogRepo.save(log);
 *
 *         // Return the value for the main entity being imported
 *         return ResolveResult.success(productSku);
 *     }
 * }
 * }</pre>
 *
 * <b>Configuration in ColumnMapping</b>
 * <pre>{@code
 * {
 *   "sourceColumn": "product_code",
 *   "targetField": "sku",
 *   "rowValueResolverName": "skuResolver"
 * }
 * }</pre>
 *
 * <b>Available Context Information</b>
 * <p>The {@code ImportContext} provides access to:</p>
 * <ul>
 *   <li>{@code getProfile()} - The ImportProfile configuration</li>
 *   <li>{@code getTargetClass()} - The class being imported</li>
 *   <li>{@code getRealmId()} - The realm/tenant ID</li>
 *   <li>{@code getRowNumber()} - Current row number (1-based)</li>
 *   <li>{@code getSessionId()} - The import session ID</li>
 * </ul>
 *
 * <b>Result Types</b>
 * <ul>
 *   <li>{@code ResolveResult.success(value)} - Use the resolved value</li>
 *   <li>{@code ResolveResult.passthrough(original)} - Keep the original value</li>
 *   <li>{@code ResolveResult.nullValue()} - Set field to null</li>
 *   <li>{@code ResolveResult.skip()} - Skip this row (no error)</li>
 *   <li>{@code ResolveResult.skip(reason)} - Skip with reason logged</li>
 *   <li>{@code ResolveResult.error(message)} - Mark row as error</li>
 * </ul>
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

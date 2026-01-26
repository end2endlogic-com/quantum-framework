package com.e2eq.framework.model.persistent.imports;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for looking up values from another collection during CSV import.
 * Useful for resolving foreign key references.
 *
 * Example: Map customer name from CSV to customer refName in database.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@RegisterForReflection
public class LookupConfig {

    /**
     * Collection/entity class name to lookup from.
     * Can be simple name (e.g., "Customer") or fully qualified.
     */
    private String lookupCollection;

    /**
     * Field in lookup collection to match against CSV value.
     * Example: "displayName", "email", "externalId"
     */
    private String lookupMatchField;

    /**
     * Field in lookup collection to return as the mapped value.
     * Example: "refName", "id"
     */
    private String lookupReturnField;

    /**
     * Behavior when lookup fails to find a match.
     * Default: FAIL
     */
    @Builder.Default
    private LookupFailBehavior onNotFound = LookupFailBehavior.FAIL;

    /**
     * Whether to cache lookup results for performance.
     * Recommended for large imports with repeated values.
     * Default: true
     */
    @Builder.Default
    private boolean cacheLookups = true;

    /**
     * Optional filter to apply to lookup query.
     * Uses standard Quantum query syntax.
     * Example: "status:ACTIVE"
     */
    private String lookupFilter;
}

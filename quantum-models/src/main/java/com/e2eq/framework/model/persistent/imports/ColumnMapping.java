package com.e2eq.framework.model.persistent.imports;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Configuration for mapping and transforming a single CSV column during import.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@RegisterForReflection
public class ColumnMapping {

    /**
     * CSV column index (0-based) or column name if header present.
     */
    private String sourceColumn;

    /**
     * Target field name on the entity.
     * Supports nested paths (e.g., "address.city") and array notation (e.g., "tags[0]").
     */
    private String targetField;

    /**
     * Static value mappings: CSV value → target value.
     * Applied before type conversion.
     * Example: {"Y": "true", "N": "false"}
     */
    private Map<String, String> valueMappings;

    /**
     * Whether value mapping comparison is case-sensitive.
     * Default: false (case-insensitive)
     */
    @Builder.Default
    private boolean valueMappingCaseSensitive = false;

    /**
     * Behavior when CSV value not found in valueMappings.
     * Default: PASSTHROUGH
     */
    @Builder.Default
    private UnmappedValueBehavior unmappedValueBehavior = UnmappedValueBehavior.PASSTHROUGH;

    /**
     * Regex pattern for replacement.
     */
    private String regexPattern;

    /**
     * Replacement string for regex matches.
     * Supports capture group references ($1, $2, etc.)
     */
    private String regexReplacement;

    /**
     * Default value if CSV cell is empty/null.
     * Applied after all other transformations.
     */
    private String defaultValue;

    /**
     * Trim leading/trailing whitespace.
     * Default: true
     */
    @Builder.Default
    private boolean trim = true;

    /**
     * Convert empty string to null.
     * Default: true
     */
    @Builder.Default
    private boolean emptyToNull = true;

    /**
     * Case transformation: UPPER, LOWER, TITLE, NONE.
     * Default: NONE
     */
    @Builder.Default
    private CaseTransform caseTransform = CaseTransform.NONE;

    /**
     * Lookup configuration for resolving references from other collections.
     */
    private LookupConfig lookup;

    /**
     * Date/time format pattern for parsing date strings.
     * Example: "yyyy-MM-dd", "MM/dd/yyyy HH:mm:ss"
     */
    private String dateFormat;

    /**
     * Locale for date/number parsing (e.g., "en-US", "de-DE").
     */
    private String locale;

    /**
     * Name of a RowValueResolver CDI bean to invoke for this column.
     * The resolver receives the cell value plus all row data and can run
     * arbitrary code to compute the final value.
     *
     * <p>This is more flexible than static lookups - use when you need to:</p>
     * <ul>
     *   <li>Access multiple columns to compute a value</li>
     *   <li>Call external services or APIs</li>
     *   <li>Apply complex business logic</li>
     *   <li>Perform conditional transformations based on other fields</li>
     * </ul>
     *
     * <p>Example: "skuResolver" invokes a CDI bean that looks up or creates SKUs
     * based on product code, category, and vendor columns.</p>
     */
    private String rowValueResolverName;
}

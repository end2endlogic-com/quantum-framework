package com.e2eq.framework.model.persistent.imports;

/**
 * String case transformation options for CSV import column mappings.
 */
public enum CaseTransform {
    /** No case transformation */
    NONE,
    /** Convert to uppercase */
    UPPER,
    /** Convert to lowercase */
    LOWER,
    /** Convert to title case (first letter of each word capitalized) */
    TITLE
}

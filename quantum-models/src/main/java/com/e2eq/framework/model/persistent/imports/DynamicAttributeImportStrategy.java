package com.e2eq.framework.model.persistent.imports;

/**
 * Strategy for importing dynamic attributes from CSV.
 */
public enum DynamicAttributeImportStrategy {
    /**
     * Disable dynamic attribute import.
     */
    NONE,

    /**
     * Use dot-notation headers: dyn.setName.attrName[:type]
     * Best for spreadsheet-friendly imports.
     */
    DOT_NOTATION,

    /**
     * Use a single JSON column containing all dynamic attributes.
     * Best for programmatic imports.
     */
    JSON_COLUMN,

    /**
     * Use vertical format with multiple rows per entity.
     * Best for attribute-centric imports.
     */
    VERTICAL,

    /**
     * Support both DOT_NOTATION and JSON_COLUMN in same import.
     * DOT_NOTATION processed first, JSON_COLUMN merged on top.
     */
    HYBRID
}

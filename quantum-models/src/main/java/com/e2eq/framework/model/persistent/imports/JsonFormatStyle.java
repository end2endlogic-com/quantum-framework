package com.e2eq.framework.model.persistent.imports;

/**
 * JSON format style for dynamic attributes in JSON_COLUMN strategy.
 */
public enum JsonFormatStyle {
    /**
     * Compact format: {"setName":{"attrName":value,...},...}
     * Types are inferred from JSON value types.
     */
    COMPACT,

    /**
     * Full structure matching DynamicAttributeSet model:
     * [{"name":"setName","attributes":[{"name":"attrName","type":"Type","value":val}]}]
     */
    FULL
}

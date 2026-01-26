package com.e2eq.framework.model.persistent.imports;

/**
 * Strategy for merging imported dynamic attributes with existing attributes.
 */
public enum DynamicAttributeMergeStrategy {
    /**
     * Remove all existing attributes, replace with imported.
     */
    REPLACE,

    /**
     * Keep existing attributes, update matching, add new.
     */
    MERGE,

    /**
     * Keep existing attributes, only add new (no updates to existing).
     */
    APPEND
}

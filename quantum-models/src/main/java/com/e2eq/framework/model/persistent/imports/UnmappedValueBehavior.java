package com.e2eq.framework.model.persistent.imports;

/**
 * Behavior when a CSV value is not found in the value mappings.
 */
public enum UnmappedValueBehavior {
    /** Keep the original CSV value unchanged */
    PASSTHROUGH,
    /** Convert to null */
    NULL,
    /** Treat as an error */
    FAIL
}

package com.e2eq.framework.model.persistent.imports;

/**
 * Behavior when lookup fails to find a matching record during CSV import.
 */
public enum LookupFailBehavior {
    /** Mark row as error */
    FAIL,
    /** Set field to null */
    NULL,
    /** Keep original CSV value unchanged */
    PASSTHROUGH
}

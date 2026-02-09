package com.e2eq.framework.model.persistent.base;

/**
 * Status indicating whether an entity is active, inactive, or deleted.
 */
public enum ActiveStatus {
    /** Entity is active and in use. */
    ACTIVE,
    /** Entity is inactive but not deleted. */
    INACTIVE,
    /** Entity is marked for deletion or soft-deleted. */
    DELETED;

    /**
     * Returns the string value of this status.
     *
     * @return the enum name as string
     */
    public String value() {
        return name();
    }

    /**
     * Parses a string value to the corresponding ActiveStatus.
     *
     * @param v the string value (e.g. ACTIVE, INACTIVE, DELETED)
     * @return the matching ActiveStatus
     */
    public static ActiveStatus fromValue(String v) {
        return valueOf(v);
    }

}

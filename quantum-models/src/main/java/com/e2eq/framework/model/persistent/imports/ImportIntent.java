package com.e2eq.framework.model.persistent.imports;

/**
 * Import intent for CSV rows.
 * Defines what action to take when processing a row.
 *
 * Note: MERGE and DELETE are intentionally excluded for safety reasons.
 * The system uses UPSERT behavior by default (full document replacement).
 */
public enum ImportIntent {
    /**
     * Insert a new record. Fails if a record with the same refName already exists.
     */
    INSERT,

    /**
     * Update an existing record. Fails if the record doesn't exist.
     */
    UPDATE,

    /**
     * Skip this row - do not process.
     */
    SKIP,

    /**
     * Insert or update (default behavior).
     * If record exists, update it; otherwise, insert new.
     */
    UPSERT
}

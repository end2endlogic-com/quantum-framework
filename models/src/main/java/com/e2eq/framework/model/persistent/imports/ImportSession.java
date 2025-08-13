package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Persistent Import Session to support CSVImportHelper preview/commit flow.
 * Stores the analyzed row results as a JSON string to avoid complex polymorphic mappings.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
@Entity
public class ImportSession extends UnversionedBaseModel {
    /**
     * Fully qualified class name of the target entity being imported.
     */
    private String targetType;

    /**
     * Status of the session: OPEN, COMPLETED, CANCELLED
     */
    private String status;

    // Summary metrics for quick UI display
    private int totalRows;
    private int validRows;
    private int errorRows;
    private int insertCount;
    private int updateCount;

    /** The user that initiated/owns this import session */
    private String userId;

    /** The collection/entity this session is for (typically the persistent class simple name) */
    private String collectionName;

    /** When the session was started (UTC) */
    private java.time.Instant startedAt;

    @Override
    public String bmFunctionalArea() {
        return "IMPORTS";
    }

    @Override
    public String bmFunctionalDomain() {
        return "IMPORTS";
    }
}

package com.e2eq.framework.imports.spi;

import com.e2eq.framework.model.persistent.imports.ImportProfile;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Context object passed to FieldCalculators and PreValidationTransformers.
 * Contains the import profile and additional metadata about the current import.
 */
@Data
@Builder
public class ImportContext {

    /**
     * The import profile being used (may be null for legacy imports).
     */
    private ImportProfile profile;

    /**
     * The target class being imported.
     */
    private Class<?> targetClass;

    /**
     * The realm ID for the import.
     */
    private String realmId;

    /**
     * The current row number (1-based, excluding header).
     */
    private int rowNumber;

    /**
     * Total number of rows in the import (if known).
     */
    private Integer totalRows;

    /**
     * Session ID for preview-commit workflow.
     */
    private String sessionId;

    /**
     * Additional custom attributes.
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    /**
     * Get a custom attribute.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Set a custom attribute.
     */
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    /**
     * Check if a custom attribute exists.
     */
    public boolean hasAttribute(String key) {
        return attributes != null && attributes.containsKey(key);
    }
}

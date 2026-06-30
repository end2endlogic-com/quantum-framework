package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-only wrapper over the values of an ingested source row together with the
 * source-binding metadata (sourceId, entityType) used by the
 * {@link com.e2eq.framework.model.security.DataDomainPolicyEntry.ResolutionMode#FROM_SOURCE}
 * resolution path.
 *
 * <p>This is a plain value holder with no persistence or framework coupling so the
 * FROM_SOURCE resolution can be exercised in pure unit tests (no Mongo, no Quarkus boot).</p>
 */
public final class SourceAttributes {

    private final String sourceId;
    private final String entityType;
    private final Map<String, Object> values;

    public SourceAttributes(String sourceId, String entityType, Map<String, Object> values) {
        this.sourceId = sourceId;
        this.entityType = entityType;
        // Defensive copy; the wrapper is read-only from the caller's perspective.
        this.values = (values == null)
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(values));
    }

    /** The id of the source/binding this row was ingested from. May be null. */
    public String getSourceId() {
        return sourceId;
    }

    /** The entity type of the ingested row. May be null. */
    public String getEntityType() {
        return entityType;
    }

    /** Raw value of a named attribute, or null if absent. */
    public Object get(String attributeName) {
        if (attributeName == null) {
            return null;
        }
        return values.get(attributeName);
    }

    /** True iff the named attribute is present (non-null value). */
    public boolean has(String attributeName) {
        return attributeName != null && values.get(attributeName) != null;
    }

    /** Immutable view of all ingested attribute values. */
    public Map<String, Object> getValues() {
        return values;
    }
}

package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for mapping a CSV column to a dynamic attribute.
 * Used for explicit mappings when auto-discovery is disabled,
 * or to override inferred types/settings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@RegisterForReflection
public class DynamicAttributeMapping {

    /**
     * The CSV column name or pattern.
     * For DOT_NOTATION: can use "dyn.{setName}.{attrName}" for auto-discovery.
     * For JSON_COLUMN: the column containing JSON.
     * For VERTICAL: not used (uses verticalConfig).
     */
    private String sourceColumn;

    /**
     * Target attribute set name. If null, extracted from header.
     */
    private String targetSetName;

    /**
     * Target attribute name. If null, extracted from header.
     */
    private String targetAttributeName;

    /**
     * Force a specific type. If null, inferred from value or definition.
     */
    private DynamicAttributeType type;

    /**
     * Date/time format for Date/DateTime types.
     * Overrides the profile-level format.
     */
    private String dateFormat;

    /**
     * DateTime format for DateTime types.
     * Overrides the profile-level format.
     */
    private String dateTimeFormat;

    /**
     * Reference to DynamicAttributeSetDefinition for validation of this specific mapping.
     * Overrides the profile-level definition.
     */
    private String definitionRefName;
}

package com.e2eq.framework.model.persistent.imports;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Configuration for VERTICAL dynamic attribute import strategy.
 * In vertical format, each row represents one attribute, and rows are grouped by entity key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@RegisterForReflection
public class VerticalFormatConfig {

    /**
     * Column containing the entity key for grouping rows.
     * Default: "_entityKey"
     */
    @Builder.Default
    private String entityKeyColumn = "_entityKey";

    /**
     * Field on the entity that matches entityKeyColumn values.
     * Used for lookups when updating existing entities.
     * Default: "refName"
     */
    @Builder.Default
    private String entityKeyField = "refName";

    /**
     * Column containing the attribute set name.
     * Default: "_attrSet"
     */
    @Builder.Default
    private String setNameColumn = "_attrSet";

    /**
     * Column containing the attribute name.
     * Default: "_attrName"
     */
    @Builder.Default
    private String attrNameColumn = "_attrName";

    /**
     * Column containing the attribute type (optional).
     * If not present or empty, type will be inferred.
     * Default: "_attrType"
     */
    @Builder.Default
    private String attrTypeColumn = "_attrType";

    /**
     * Column containing the attribute value.
     * Default: "_attrValue"
     */
    @Builder.Default
    private String attrValueColumn = "_attrValue";

    /**
     * If true, entity fields can be included on each row.
     * First occurrence of each field value is used.
     * Default: false
     */
    @Builder.Default
    private boolean allowEntityFieldsOnRows = false;

    /**
     * Columns to exclude from entity field processing.
     * Typically the attribute columns themselves.
     * If null, defaults to the attribute columns (entityKeyColumn, setNameColumn, etc.)
     */
    private List<String> excludeFromEntityFields;

    /**
     * Default set name to use when setNameColumn is null or empty.
     */
    private String defaultSetName;
}

package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * ImportProfile defines the configuration for CSV import transformations.
 * Profiles can be saved and reused across multiple imports.
 *
 * Features:
 * - Column mappings with value transformations
 * - Cross-collection lookups
 * - Global string transformations
 * - Header modifier parsing (*, ?, ~, #)
 * - Inline field calculators
 * - Intent column support
 * - Dynamic attribute import (DOT_NOTATION, JSON_COLUMN, VERTICAL, HYBRID)
 */
@Entity(value = "importProfiles", useDiscriminator = false)
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Data
@NoArgsConstructor
@ToString(callSuper = true)
public class ImportProfile extends BaseModel {

    /**
     * Target collection/entity type for this profile.
     * Example: "com.e2eq.framework.model.persistent.morphia.ContactInfo"
     */
    @NotBlank(message = "Target collection is required")
    private String targetCollection;

    /**
     * Human-readable description of this profile.
     */
    private String description;

    /**
     * Column mappings for transformations.
     * Each mapping defines how a CSV column is transformed before validation.
     */
    @Valid
    private List<ColumnMapping> columnMappings;

    /**
     * Global transformations applied to all string fields.
     * Applied before column-specific transformations.
     */
    @Valid
    private GlobalTransformations globalTransformations;

    /**
     * CSV column name containing the row intent (INSERT, UPDATE, SKIP, UPSERT).
     * If null, defaultIntent is used for all rows.
     */
    private String intentColumn;

    /**
     * Default intent when intentColumn is not specified or value is empty.
     * Defaults to UPSERT for backward compatibility.
     */
    private ImportIntent defaultIntent;

    /**
     * Enable parsing of header modifiers (*, ?, ~, #).
     * When false, headers are used as-is (backward compatible).
     * Default: false
     */
    private boolean enableHeaderModifiers;

    /**
     * Inline field calculators for computed fields.
     * These generate values for fields not in the CSV.
     */
    @Valid
    private List<InlineFieldCalculator> inlineCalculators;

    /**
     * Names of FieldCalculator CDI beans to invoke.
     * These are loaded via SPI/CDI for extensibility.
     */
    private List<String> fieldCalculatorNames;

    /**
     * Names of PreValidationTransformer CDI beans to invoke.
     * These are loaded via SPI/CDI for extensibility.
     */
    private List<String> preValidationTransformerNames;

    /**
     * CSV delimiter character.
     * Default: comma
     */
    private Character delimiter;

    /**
     * CSV quote character.
     * Default: double quote
     */
    private Character quoteChar;

    /**
     * Whether the CSV has a header row.
     * Default: true
     */
    private Boolean hasHeader;

    /**
     * Number of header rows to skip (for multi-line headers).
     * Default: 1 if hasHeader is true
     */
    private Integer skipHeaderRows;

    /**
     * Character encoding of the CSV file.
     * Default: UTF-8
     */
    private String encoding;

    // ==================== Dynamic Attribute Configuration ====================

    /**
     * Strategy for importing dynamic attributes from CSV.
     * Default: NONE (disabled)
     */
    private DynamicAttributeImportStrategy dynamicAttributeStrategy;

    /**
     * How to merge imported dynamic attributes with existing ones.
     * Default: MERGE
     */
    private DynamicAttributeMergeStrategy dynamicAttributeMergeStrategy;

    /**
     * Prefix for identifying dynamic attribute columns in DOT_NOTATION strategy.
     * Default: "dyn."
     */
    private String dynamicAttributePrefix;

    /**
     * Enable auto-discovery of dynamic attribute columns by prefix.
     * When true, columns matching the prefix pattern are automatically processed.
     * Default: true
     */
    private Boolean enableDynamicAttributeDiscovery;

    /**
     * Configuration for JSON_COLUMN strategy.
     * Specifies which column contains JSON and how to parse it.
     */
    @Valid
    private JsonColumnConfig jsonColumnConfig;

    /**
     * Configuration for VERTICAL strategy.
     * Specifies the entity key, attribute name, and value columns.
     */
    @Valid
    private VerticalFormatConfig verticalFormatConfig;

    /**
     * Explicit mappings for dynamic attribute columns.
     * Used when auto-discovery is disabled or to override inferred settings.
     */
    @Valid
    private List<DynamicAttributeMapping> dynamicAttributeMappings;

    /**
     * Reference to a DynamicAttributeSetDefinition for validation.
     * When specified, imported attributes are validated against this definition.
     */
    private String dynamicAttributeDefinitionRefName;

    /**
     * Enable strict validation against the definition.
     * When true, attributes not in the definition are rejected.
     * Default: false
     */
    private Boolean strictDynamicAttributeValidation;

    /**
     * Default type for dynamic attributes when not specified or inferred.
     * Default: String
     */
    private DynamicAttributeType defaultDynamicAttributeType;

    /**
     * Date format for dynamic attribute Date type parsing.
     * Default: ISO_LOCAL_DATE (yyyy-MM-dd)
     */
    private String dynamicAttributeDateFormat;

    /**
     * DateTime format for dynamic attribute DateTime type parsing.
     * Default: ISO_LOCAL_DATE_TIME (yyyy-MM-dd'T'HH:mm:ss)
     */
    private String dynamicAttributeDateTimeFormat;

    /**
     * Get column mappings, initializing if null.
     */
    public List<ColumnMapping> getColumnMappings() {
        if (columnMappings == null) {
            columnMappings = new ArrayList<>();
        }
        return columnMappings;
    }

    /**
     * Get inline calculators, initializing if null.
     */
    public List<InlineFieldCalculator> getInlineCalculators() {
        if (inlineCalculators == null) {
            inlineCalculators = new ArrayList<>();
        }
        return inlineCalculators;
    }

    /**
     * Get field calculator names, initializing if null.
     */
    public List<String> getFieldCalculatorNames() {
        if (fieldCalculatorNames == null) {
            fieldCalculatorNames = new ArrayList<>();
        }
        return fieldCalculatorNames;
    }

    /**
     * Get pre-validation transformer names, initializing if null.
     */
    public List<String> getPreValidationTransformerNames() {
        if (preValidationTransformerNames == null) {
            preValidationTransformerNames = new ArrayList<>();
        }
        return preValidationTransformerNames;
    }

    /**
     * Get the default intent, defaulting to UPSERT for backward compatibility.
     */
    public ImportIntent getDefaultIntent() {
        return defaultIntent != null ? defaultIntent : ImportIntent.UPSERT;
    }

    /**
     * Get the delimiter, defaulting to comma.
     */
    public char getDelimiterChar() {
        return delimiter != null ? delimiter : ',';
    }

    /**
     * Get the quote character, defaulting to double quote.
     */
    public char getQuoteCharChar() {
        return quoteChar != null ? quoteChar : '"';
    }

    /**
     * Check if CSV has header row, defaulting to true.
     */
    public boolean hasHeaderRow() {
        return hasHeader == null || hasHeader;
    }

    /**
     * Get encoding, defaulting to UTF-8.
     */
    public String getEncodingOrDefault() {
        return encoding != null ? encoding : "UTF-8";
    }

    /**
     * Find a column mapping by source column name.
     */
    public ColumnMapping findMappingBySourceColumn(String sourceColumn) {
        if (sourceColumn == null || columnMappings == null) {
            return null;
        }
        return columnMappings.stream()
                .filter(m -> sourceColumn.equals(m.getSourceColumn()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find a column mapping by target field name.
     */
    public ColumnMapping findMappingByTargetField(String targetField) {
        if (targetField == null || columnMappings == null) {
            return null;
        }
        return columnMappings.stream()
                .filter(m -> targetField.equals(m.getTargetField()))
                .findFirst()
                .orElse(null);
    }

    // ==================== Dynamic Attribute Helper Methods ====================

    /**
     * Get the dynamic attribute strategy, defaulting to NONE.
     */
    public DynamicAttributeImportStrategy getDynamicAttributeStrategyOrDefault() {
        return dynamicAttributeStrategy != null ? dynamicAttributeStrategy : DynamicAttributeImportStrategy.NONE;
    }

    /**
     * Get the dynamic attribute merge strategy, defaulting to MERGE.
     */
    public DynamicAttributeMergeStrategy getDynamicAttributeMergeStrategyOrDefault() {
        return dynamicAttributeMergeStrategy != null ? dynamicAttributeMergeStrategy : DynamicAttributeMergeStrategy.MERGE;
    }

    /**
     * Get the dynamic attribute prefix, defaulting to "dyn.".
     */
    public String getDynamicAttributePrefixOrDefault() {
        return dynamicAttributePrefix != null ? dynamicAttributePrefix : "dyn.";
    }

    /**
     * Check if dynamic attribute discovery is enabled, defaulting to true.
     */
    public boolean isDynamicAttributeDiscoveryEnabled() {
        return enableDynamicAttributeDiscovery == null || enableDynamicAttributeDiscovery;
    }

    /**
     * Check if strict validation is enabled, defaulting to false.
     */
    public boolean isStrictDynamicAttributeValidationEnabled() {
        return strictDynamicAttributeValidation != null && strictDynamicAttributeValidation;
    }

    /**
     * Get the default dynamic attribute type, defaulting to String.
     */
    public DynamicAttributeType getDefaultDynamicAttributeTypeOrDefault() {
        return defaultDynamicAttributeType != null ? defaultDynamicAttributeType : DynamicAttributeType.String;
    }

    /**
     * Get the date format for dynamic attributes, defaulting to ISO format.
     */
    public String getDynamicAttributeDateFormatOrDefault() {
        return dynamicAttributeDateFormat != null ? dynamicAttributeDateFormat : "yyyy-MM-dd";
    }

    /**
     * Get the datetime format for dynamic attributes, defaulting to ISO format.
     */
    public String getDynamicAttributeDateTimeFormatOrDefault() {
        return dynamicAttributeDateTimeFormat != null ? dynamicAttributeDateTimeFormat : "yyyy-MM-dd'T'HH:mm:ss";
    }

    /**
     * Get the dynamic attribute mappings, initializing if null.
     */
    public List<DynamicAttributeMapping> getDynamicAttributeMappings() {
        if (dynamicAttributeMappings == null) {
            dynamicAttributeMappings = new ArrayList<>();
        }
        return dynamicAttributeMappings;
    }

    /**
     * Check if dynamic attribute import is enabled for this profile.
     */
    public boolean isDynamicAttributeImportEnabled() {
        return dynamicAttributeStrategy != null && dynamicAttributeStrategy != DynamicAttributeImportStrategy.NONE;
    }

    /**
     * Check if a column header matches the dynamic attribute prefix pattern.
     */
    public boolean isDynamicAttributeColumn(String header) {
        if (header == null || !isDynamicAttributeImportEnabled()) {
            return false;
        }
        String prefix = getDynamicAttributePrefixOrDefault();
        return header.startsWith(prefix);
    }

    /**
     * Find explicit mapping for a dynamic attribute column.
     */
    public DynamicAttributeMapping findDynamicAttributeMapping(String sourceColumn) {
        if (sourceColumn == null || dynamicAttributeMappings == null) {
            return null;
        }
        return dynamicAttributeMappings.stream()
                .filter(m -> sourceColumn.equals(m.getSourceColumn()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String bmFunctionalArea() {
        return "IMPORTS";
    }

    @Override
    public String bmFunctionalDomain() {
        return "IMPORTS";
    }
}

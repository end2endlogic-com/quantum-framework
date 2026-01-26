package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.BaseModel;
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

    @Override
    public String bmFunctionalArea() {
        return "IMPORTS";
    }

    @Override
    public String bmFunctionalDomain() {
        return "IMPORTS";
    }
}

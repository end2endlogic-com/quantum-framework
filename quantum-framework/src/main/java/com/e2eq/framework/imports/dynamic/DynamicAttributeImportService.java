package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.imports.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Main orchestrator service for dynamic attribute import.
 * Coordinates the processing of dynamic attributes from CSV based on the configured strategy.
 *
 * Supports strategies:
 * - NONE: No dynamic attribute processing
 * - DOT_NOTATION: Headers like dyn.setName.attrName[:type]
 * - JSON_COLUMN: Single column containing JSON
 * - VERTICAL: Multiple rows per entity
 * - HYBRID: Both DOT_NOTATION and JSON_COLUMN (dot notation first, JSON merged on top)
 */
@ApplicationScoped
public class DynamicAttributeImportService {

    private static final Logger LOG = Logger.getLogger(DynamicAttributeImportService.class);

    @Inject
    DotNotationProcessor dotNotationProcessor;

    @Inject
    JsonColumnProcessor jsonColumnProcessor;

    @Inject
    VerticalFormatProcessor verticalFormatProcessor;

    @Inject
    DynamicAttributeMerger merger;

    /**
     * Context for processing a single row.
     */
    public static class RowContext {
        private final String[] headers;
        private final Map<Integer, ParsedDynamicHeader> parsedHeaders;
        private final ImportProfile profile;

        public RowContext(String[] headers, Map<Integer, ParsedDynamicHeader> parsedHeaders, ImportProfile profile) {
            this.headers = headers;
            this.parsedHeaders = parsedHeaders;
            this.profile = profile;
        }

        public String[] getHeaders() {
            return headers;
        }

        public Map<Integer, ParsedDynamicHeader> getParsedHeaders() {
            return parsedHeaders;
        }

        public ImportProfile getProfile() {
            return profile;
        }
    }

    /**
     * Initialize processing context for a CSV file.
     * Call this once before processing rows.
     *
     * @param headers the CSV headers
     * @param profile the import profile
     * @return row context for use with processRow
     */
    public RowContext initializeContext(String[] headers, ImportProfile profile) {
        DynamicAttributeImportStrategy strategy = profile.getDynamicAttributeStrategyOrDefault();

        Map<Integer, ParsedDynamicHeader> parsedHeaders = Collections.emptyMap();

        if (strategy == DynamicAttributeImportStrategy.DOT_NOTATION ||
                strategy == DynamicAttributeImportStrategy.HYBRID) {
            parsedHeaders = dotNotationProcessor.parseHeaders(headers, profile);
            LOG.debugf("Parsed %d dynamic attribute headers", parsedHeaders.size());
        }

        return new RowContext(headers, parsedHeaders, profile);
    }

    /**
     * Process a single row and extract dynamic attributes.
     *
     * @param rowData the row data as column name -> value map
     * @param context the processing context
     * @param existing existing dynamic attribute sets (for merge)
     * @return merged dynamic attribute sets
     */
    public List<DynamicAttributeSet> processRow(Map<String, Object> rowData,
                                                 RowContext context,
                                                 List<DynamicAttributeSet> existing) {
        ImportProfile profile = context.getProfile();
        DynamicAttributeImportStrategy strategy = profile.getDynamicAttributeStrategyOrDefault();

        if (strategy == DynamicAttributeImportStrategy.NONE) {
            return existing != null ? existing : new ArrayList<>();
        }

        Map<String, DynamicAttributeSet> imported = new HashMap<>();

        // Process based on strategy
        switch (strategy) {
            case DOT_NOTATION:
                imported = processDotNotation(rowData, context);
                break;

            case JSON_COLUMN:
                imported = processJsonColumn(rowData, profile);
                break;

            case HYBRID:
                // Process DOT_NOTATION first
                imported = processDotNotation(rowData, context);
                // Then merge JSON on top
                Map<String, DynamicAttributeSet> jsonImported = processJsonColumn(rowData, profile);
                imported = merger.mergeMaps(imported, jsonImported,
                        profile.getDynamicAttributeMergeStrategyOrDefault());
                break;

            case VERTICAL:
                // VERTICAL is handled differently (multiple rows per entity)
                LOG.warn("VERTICAL strategy should not be processed row-by-row; use processVertical()");
                break;

            case NONE:
            default:
                // No processing
                break;
        }

        // Merge with existing
        return merger.merge(existing, imported, profile.getDynamicAttributeMergeStrategyOrDefault());
    }

    /**
     * Process DOT_NOTATION strategy for a row.
     */
    private Map<String, DynamicAttributeSet> processDotNotation(Map<String, Object> rowData,
                                                                 RowContext context) {
        return dotNotationProcessor.processRow(rowData,
                context.getParsedHeaders(),
                context.getHeaders(),
                context.getProfile());
    }

    /**
     * Process JSON_COLUMN strategy for a row.
     */
    private Map<String, DynamicAttributeSet> processJsonColumn(Map<String, Object> rowData,
                                                                ImportProfile profile) {
        String jsonColumnName = jsonColumnProcessor.getJsonColumnName(profile);
        if (jsonColumnName == null) {
            return Collections.emptyMap();
        }

        Object jsonValue = rowData.get(jsonColumnName);
        if (jsonValue == null) {
            return Collections.emptyMap();
        }

        return jsonColumnProcessor.processJson(jsonValue.toString(), profile);
    }

    /**
     * Process VERTICAL format CSV.
     * This processes all rows at once and groups by entity.
     *
     * @param rows all rows from the CSV
     * @param profile the import profile
     * @return result containing attributes grouped by entity key
     */
    public VerticalFormatProcessor.VerticalProcessingResult processVertical(
            List<Map<String, Object>> rows, ImportProfile profile) {
        return verticalFormatProcessor.processRows(rows, profile);
    }

    /**
     * Get dynamic attributes for a specific entity from vertical processing result.
     *
     * @param result the vertical processing result
     * @param entityKey the entity key
     * @param existing existing dynamic attribute sets
     * @param profile the import profile
     * @return merged dynamic attribute sets
     */
    public List<DynamicAttributeSet> getVerticalAttributesForEntity(
            VerticalFormatProcessor.VerticalProcessingResult result,
            String entityKey,
            List<DynamicAttributeSet> existing,
            ImportProfile profile) {

        Map<String, DynamicAttributeSet> imported = verticalFormatProcessor.getAttributesForEntity(result, entityKey);
        return merger.merge(existing, imported, profile.getDynamicAttributeMergeStrategyOrDefault());
    }

    /**
     * Check if the import profile has dynamic attribute import enabled.
     *
     * @param profile the import profile
     * @return true if dynamic attribute import is enabled
     */
    public boolean isEnabled(ImportProfile profile) {
        return profile != null && profile.isDynamicAttributeImportEnabled();
    }

    /**
     * Get the list of column names to exclude from regular field mapping
     * (because they are dynamic attribute columns).
     *
     * @param headers the CSV headers
     * @param profile the import profile
     * @return list of column names to exclude
     */
    public List<String> getDynamicColumnNames(String[] headers, ImportProfile profile) {
        if (!isEnabled(profile)) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        DynamicAttributeImportStrategy strategy = profile.getDynamicAttributeStrategyOrDefault();

        // Add DOT_NOTATION columns
        if (strategy == DynamicAttributeImportStrategy.DOT_NOTATION ||
                strategy == DynamicAttributeImportStrategy.HYBRID) {
            result.addAll(dotNotationProcessor.getDynamicColumnNames(headers, profile));
        }

        // Add JSON column
        if (strategy == DynamicAttributeImportStrategy.JSON_COLUMN ||
                strategy == DynamicAttributeImportStrategy.HYBRID) {
            String jsonColumn = jsonColumnProcessor.getJsonColumnName(profile);
            if (jsonColumn != null) {
                result.add(jsonColumn);
            }
        }

        // VERTICAL strategy columns are handled separately

        return result;
    }

    /**
     * Validate the dynamic attribute configuration in an import profile.
     *
     * @param profile the import profile
     * @return list of validation errors (empty if valid)
     */
    public List<String> validateConfiguration(ImportProfile profile) {
        List<String> errors = new ArrayList<>();

        if (profile == null) {
            errors.add("ImportProfile is null");
            return errors;
        }

        DynamicAttributeImportStrategy strategy = profile.getDynamicAttributeStrategyOrDefault();

        if (strategy == DynamicAttributeImportStrategy.NONE) {
            return errors; // No validation needed
        }

        // Validate JSON_COLUMN configuration
        if (strategy == DynamicAttributeImportStrategy.JSON_COLUMN ||
                strategy == DynamicAttributeImportStrategy.HYBRID) {
            if (strategy == DynamicAttributeImportStrategy.JSON_COLUMN &&
                    !jsonColumnProcessor.isConfigured(profile)) {
                errors.add("JSON_COLUMN strategy requires jsonColumnConfig with columnName");
            }
        }

        // Validate VERTICAL configuration
        if (strategy == DynamicAttributeImportStrategy.VERTICAL) {
            errors.addAll(verticalFormatProcessor.validateConfiguration(profile));
        }

        return errors;
    }

    /**
     * Check if any dynamic attribute columns exist in the headers.
     *
     * @param headers the CSV headers
     * @param profile the import profile
     * @return true if dynamic attribute columns are present
     */
    public boolean hasDynamicColumns(String[] headers, ImportProfile profile) {
        if (!isEnabled(profile)) {
            return false;
        }

        DynamicAttributeImportStrategy strategy = profile.getDynamicAttributeStrategyOrDefault();

        if (strategy == DynamicAttributeImportStrategy.DOT_NOTATION ||
                strategy == DynamicAttributeImportStrategy.HYBRID) {
            if (dotNotationProcessor.hasDynamicColumns(headers, profile)) {
                return true;
            }
        }

        if (strategy == DynamicAttributeImportStrategy.JSON_COLUMN ||
                strategy == DynamicAttributeImportStrategy.HYBRID) {
            String jsonColumn = jsonColumnProcessor.getJsonColumnName(profile);
            if (jsonColumn != null) {
                for (String header : headers) {
                    if (jsonColumn.equals(header)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}

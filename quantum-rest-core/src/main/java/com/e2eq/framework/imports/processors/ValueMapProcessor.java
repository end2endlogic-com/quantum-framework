package com.e2eq.framework.imports.processors;

import com.e2eq.framework.model.persistent.imports.UnmappedValueBehavior;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.util.CsvContext;

import java.util.Map;

/**
 * SuperCSV CellProcessor that maps CSV values to different values.
 * Supports case-insensitive matching and configurable unmapped value behavior.
 *
 * Example mapping: {"Y": "true", "N": "false", "YES": "true", "NO": "false"}
 */
public class ValueMapProcessor extends CellProcessorAdaptor {

    private final Map<String, String> mappings;
    private final boolean caseSensitive;
    private final UnmappedValueBehavior unmappedBehavior;

    /**
     * Create a ValueMapProcessor with default behavior (passthrough unmapped values).
     */
    public ValueMapProcessor(Map<String, String> mappings, boolean caseSensitive) {
        this(mappings, caseSensitive, UnmappedValueBehavior.PASSTHROUGH);
    }

    /**
     * Create a ValueMapProcessor with custom unmapped value behavior.
     */
    public ValueMapProcessor(Map<String, String> mappings, boolean caseSensitive,
                             UnmappedValueBehavior unmappedBehavior) {
        this.mappings = mappings;
        this.caseSensitive = caseSensitive;
        this.unmappedBehavior = unmappedBehavior;
    }

    /**
     * Create a ValueMapProcessor with a next processor in the chain.
     */
    public ValueMapProcessor(Map<String, String> mappings, boolean caseSensitive,
                             UnmappedValueBehavior unmappedBehavior, CellProcessor next) {
        super(next);
        this.mappings = mappings;
        this.caseSensitive = caseSensitive;
        this.unmappedBehavior = unmappedBehavior;
    }

    @Override
    public Object execute(Object value, CsvContext context) {
        validateInputNotNull(value, context);

        String stringValue = value.toString();
        String lookupKey = caseSensitive ? stringValue : stringValue.toUpperCase();

        // Look up in mappings
        String mappedValue = null;
        if (caseSensitive) {
            mappedValue = mappings.get(lookupKey);
        } else {
            // Case-insensitive lookup
            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(lookupKey)) {
                    mappedValue = entry.getValue();
                    break;
                }
            }
        }

        if (mappedValue != null) {
            return next.execute(mappedValue, context);
        }

        // Handle unmapped value
        switch (unmappedBehavior) {
            case NULL:
                return next.execute(null, context);
            case FAIL:
                throw new org.supercsv.exception.SuperCsvCellProcessorException(
                        String.format("Value '%s' not found in mapping", stringValue),
                        context, this);
            case PASSTHROUGH:
            default:
                return next.execute(value, context);
        }
    }
}

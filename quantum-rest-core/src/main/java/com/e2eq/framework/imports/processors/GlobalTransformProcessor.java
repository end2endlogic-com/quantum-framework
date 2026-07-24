package com.e2eq.framework.imports.processors;

import com.e2eq.framework.model.persistent.imports.GlobalTransformations;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.util.CsvContext;

import java.text.Normalizer;

/**
 * SuperCSV CellProcessor that applies global string transformations.
 * Applies trimming, empty-to-null, unicode normalization, etc.
 */
public class GlobalTransformProcessor extends CellProcessorAdaptor {

    private final GlobalTransformations config;

    /**
     * Create a GlobalTransformProcessor.
     *
     * @param config the global transformations configuration
     */
    public GlobalTransformProcessor(GlobalTransformations config) {
        this.config = config;
    }

    /**
     * Create a GlobalTransformProcessor with a next processor in the chain.
     */
    public GlobalTransformProcessor(GlobalTransformations config, CellProcessor next) {
        super(next);
        this.config = config;
    }

    @Override
    public Object execute(Object value, CsvContext context) {
        if (value == null) {
            return next.execute(null, context);
        }

        // Only process strings
        if (!(value instanceof String)) {
            return next.execute(value, context);
        }

        String result = (String) value;

        // Trim whitespace
        if (config.isTrimStrings()) {
            result = result.trim();
        }

        // Empty to null
        if (config.isEmptyStringsToNull() && result.isEmpty()) {
            return next.execute(null, context);
        }

        // Unicode normalization
        String normForm = config.getUnicodeNormalization();
        if (normForm != null && !normForm.isEmpty()) {
            try {
                Normalizer.Form form = Normalizer.Form.valueOf(normForm);
                result = Normalizer.normalize(result, form);
            } catch (IllegalArgumentException e) {
                // Invalid form, skip normalization
            }
        }

        // Remove control characters
        if (config.isRemoveControlChars()) {
            result = removeControlChars(result);
        }

        // Normalize whitespace
        if (config.isNormalizeWhitespace()) {
            result = result.replaceAll("\\s+", " ");
        }

        // Max string length
        Integer maxLength = config.getMaxStringLength();
        if (maxLength != null && maxLength > 0 && result.length() > maxLength) {
            result = result.substring(0, maxLength);
        }

        return next.execute(result, context);
    }

    private String removeControlChars(String input) {
        if (input == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            // Keep tab (0x09), newline (0x0A), carriage return (0x0D)
            if (c >= 0x20 || c == 0x09 || c == 0x0A || c == 0x0D) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

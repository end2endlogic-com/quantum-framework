package com.e2eq.framework.imports.processors;

import com.e2eq.framework.model.persistent.imports.CaseTransform;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.util.CsvContext;

/**
 * SuperCSV CellProcessor that transforms string case.
 * Supports UPPER, LOWER, and TITLE case transformations.
 */
public class CaseTransformProcessor extends CellProcessorAdaptor {

    private final CaseTransform transform;

    /**
     * Create a CaseTransformProcessor.
     *
     * @param transform the case transformation to apply
     */
    public CaseTransformProcessor(CaseTransform transform) {
        this.transform = transform;
    }

    /**
     * Create a CaseTransformProcessor with a next processor in the chain.
     */
    public CaseTransformProcessor(CaseTransform transform, CellProcessor next) {
        super(next);
        this.transform = transform;
    }

    @Override
    public Object execute(Object value, CsvContext context) {
        if (value == null) {
            return next.execute(null, context);
        }

        String stringValue = value.toString();

        String result = switch (transform) {
            case UPPER -> stringValue.toUpperCase();
            case LOWER -> stringValue.toLowerCase();
            case TITLE -> toTitleCase(stringValue);
            case NONE -> stringValue;
        };

        return next.execute(result, context);
    }

    private String toTitleCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;

        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c) || c == '-' || c == '_') {
                capitalizeNext = true;
                result.append(c);
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }

        return result.toString();
    }
}

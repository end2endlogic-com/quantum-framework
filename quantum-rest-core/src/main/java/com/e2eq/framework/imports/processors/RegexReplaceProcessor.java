package com.e2eq.framework.imports.processors;

import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.util.CsvContext;

import java.util.regex.Pattern;

/**
 * SuperCSV CellProcessor that applies regex replacement to values.
 * Useful for normalizing data formats (e.g., phone numbers, IDs).
 *
 * Example: pattern="[^0-9]", replacement="" removes all non-digits
 */
public class RegexReplaceProcessor extends CellProcessorAdaptor {

    private final Pattern pattern;
    private final String replacement;

    /**
     * Create a RegexReplaceProcessor.
     *
     * @param regex the regex pattern to match
     * @param replacement the replacement string
     */
    public RegexReplaceProcessor(String regex, String replacement) {
        this.pattern = Pattern.compile(regex);
        this.replacement = replacement != null ? replacement : "";
    }

    /**
     * Create a RegexReplaceProcessor with a next processor in the chain.
     */
    public RegexReplaceProcessor(String regex, String replacement, CellProcessor next) {
        super(next);
        this.pattern = Pattern.compile(regex);
        this.replacement = replacement != null ? replacement : "";
    }

    @Override
    public Object execute(Object value, CsvContext context) {
        if (value == null) {
            return next.execute(null, context);
        }

        String stringValue = value.toString();
        String result = pattern.matcher(stringValue).replaceAll(replacement);

        return next.execute(result, context);
    }
}

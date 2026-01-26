package com.e2eq.framework.model.persistent.imports;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a parsed CSV header with its modifier.
 * Parses headers like "fieldName*" into field name "fieldName" and modifier REQUIRED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@RegisterForReflection
public class ParsedHeader {

    /**
     * The base field name (without modifier suffix).
     */
    private String fieldName;

    /**
     * The original header as it appeared in the CSV.
     */
    private String originalHeader;

    /**
     * The parsed modifier from the header suffix.
     */
    @Builder.Default
    private HeaderModifier modifier = HeaderModifier.NONE;

    /**
     * Parse a CSV header string into a ParsedHeader.
     * If header modifiers are not enabled, returns the header as-is with NONE modifier.
     *
     * @param header the raw header string from CSV
     * @param enableModifiers whether to parse modifier suffixes
     * @return the parsed header
     */
    public static ParsedHeader parse(String header, boolean enableModifiers) {
        if (header == null || header.isEmpty()) {
            return ParsedHeader.builder()
                    .fieldName("")
                    .originalHeader(header)
                    .modifier(HeaderModifier.NONE)
                    .build();
        }

        String trimmed = header.trim();

        if (!enableModifiers || trimmed.length() < 2) {
            return ParsedHeader.builder()
                    .fieldName(trimmed)
                    .originalHeader(header)
                    .modifier(HeaderModifier.NONE)
                    .build();
        }

        char lastChar = trimmed.charAt(trimmed.length() - 1);

        if (HeaderModifier.isModifierChar(lastChar)) {
            String fieldName = trimmed.substring(0, trimmed.length() - 1);
            HeaderModifier modifier = HeaderModifier.fromSuffix(String.valueOf(lastChar));

            return ParsedHeader.builder()
                    .fieldName(fieldName)
                    .originalHeader(header)
                    .modifier(modifier)
                    .build();
        }

        return ParsedHeader.builder()
                .fieldName(trimmed)
                .originalHeader(header)
                .modifier(HeaderModifier.NONE)
                .build();
    }

    /**
     * Check if this header marks a required field.
     */
    public boolean isRequired() {
        return modifier == HeaderModifier.REQUIRED;
    }

    /**
     * Check if this header marks an optional field.
     */
    public boolean isOptional() {
        return modifier == HeaderModifier.OPTIONAL;
    }

    /**
     * Check if this header marks a calculated field.
     */
    public boolean isCalculated() {
        return modifier == HeaderModifier.CALCULATED;
    }

    /**
     * Check if this header marks a key field.
     */
    public boolean isKey() {
        return modifier == HeaderModifier.KEY;
    }
}

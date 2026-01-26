package com.e2eq.framework.model.persistent.imports;

import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a parsed dynamic attribute header in DOT_NOTATION format.
 * Format: prefix.setName.attrName[:type]
 * Example: dyn.logistics.weight:double
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@RegisterForReflection
public class ParsedDynamicHeader {

    /**
     * The original header string from the CSV.
     */
    private String originalHeader;

    /**
     * The attribute set name (e.g., "logistics").
     */
    private String setName;

    /**
     * The attribute name (e.g., "weight").
     */
    private String attributeName;

    /**
     * Explicit type from header suffix (e.g., ":double"), or null if not specified.
     */
    private DynamicAttributeType type;

    /**
     * Date format for Date type parsing.
     */
    private String dateFormat;

    /**
     * DateTime format for DateTime type parsing.
     */
    private String dateTimeFormat;

    /**
     * Whether the header was parsed successfully.
     */
    private boolean valid;

    /**
     * Error message if parsing failed.
     */
    private String errorMessage;

    /**
     * Parse a header string into a ParsedDynamicHeader.
     *
     * @param header the header string (e.g., "dyn.logistics.weight:double")
     * @param prefix the dynamic attribute prefix (e.g., "dyn.")
     * @return parsed header info, or null if not a dynamic attribute column
     */
    public static ParsedDynamicHeader parse(String header, String prefix) {
        if (header == null || prefix == null || !header.startsWith(prefix)) {
            return null;
        }

        String remainder = header.substring(prefix.length());
        if (remainder.isEmpty()) {
            return ParsedDynamicHeader.builder()
                    .originalHeader(header)
                    .valid(false)
                    .errorMessage("Empty header after prefix")
                    .build();
        }

        // Parse: setName.attrName[:type]
        int typeIdx = remainder.lastIndexOf(':');
        String path;
        DynamicAttributeType type = null;

        if (typeIdx > 0) {
            path = remainder.substring(0, typeIdx);
            String typeStr = remainder.substring(typeIdx + 1);
            try {
                type = DynamicAttributeType.fromValue(typeStr);
            } catch (IllegalArgumentException e) {
                // Invalid type string, treat as part of attribute name
                path = remainder;
            }
        } else {
            path = remainder;
        }

        // Split path into setName.attrName
        int dotIdx = path.indexOf('.');
        if (dotIdx <= 0 || dotIdx >= path.length() - 1) {
            return ParsedDynamicHeader.builder()
                    .originalHeader(header)
                    .valid(false)
                    .errorMessage("Invalid format: expected dyn.setName.attrName[:type]")
                    .build();
        }

        String setName = path.substring(0, dotIdx);
        String attrName = path.substring(dotIdx + 1);

        // Validate names are not empty
        if (setName.trim().isEmpty() || attrName.trim().isEmpty()) {
            return ParsedDynamicHeader.builder()
                    .originalHeader(header)
                    .valid(false)
                    .errorMessage("Empty set name or attribute name")
                    .build();
        }

        return ParsedDynamicHeader.builder()
                .originalHeader(header)
                .setName(setName.trim())
                .attributeName(attrName.trim())
                .type(type)
                .valid(true)
                .build();
    }

    /**
     * Check if a header string is a dynamic attribute column.
     *
     * @param header the header string
     * @param prefix the dynamic attribute prefix
     * @return true if the header matches the dynamic attribute pattern
     */
    public static boolean isDynamicHeader(String header, String prefix) {
        ParsedDynamicHeader parsed = parse(header, prefix);
        return parsed != null && parsed.isValid();
    }
}

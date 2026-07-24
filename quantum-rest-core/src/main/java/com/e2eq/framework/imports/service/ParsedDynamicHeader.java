package com.e2eq.framework.imports.service;

import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a parsed dynamic attribute header in DOT_NOTATION format.
 * Format: prefix.setName.attrName[:type]
 * Example: dyn.logistics.weight:Double
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
    private String attrName;

    /**
     * Explicit type from header suffix (e.g., ":Double"), or null if not specified.
     */
    private DynamicAttributeType explicitType;

    /**
     * Parse a header string into a ParsedDynamicHeader.
     *
     * @param header the header string (e.g., "dyn.logistics.weight:Double")
     * @param prefix the dynamic attribute prefix (e.g., "dyn.")
     * @return parsed header info, or null if not a dynamic attribute column
     */
    public static ParsedDynamicHeader parse(String header, String prefix) {
        if (header == null || prefix == null || !header.startsWith(prefix)) {
            return null;
        }

        String remainder = header.substring(prefix.length());
        if (remainder.isEmpty()) {
            return null;
        }

        // Parse: setName.attrName[:type]
        int typeIdx = remainder.lastIndexOf(':');
        String path;
        DynamicAttributeType explicitType = null;

        if (typeIdx > 0) {
            path = remainder.substring(0, typeIdx);
            String typeStr = remainder.substring(typeIdx + 1);
            try {
                explicitType = DynamicAttributeType.fromValue(typeStr);
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
            return null; // Invalid format - must have both set and attr name
        }

        String setName = path.substring(0, dotIdx);
        String attrName = path.substring(dotIdx + 1);

        // Validate names are not empty
        if (setName.trim().isEmpty() || attrName.trim().isEmpty()) {
            return null;
        }

        return ParsedDynamicHeader.builder()
                .originalHeader(header)
                .setName(setName.trim())
                .attrName(attrName.trim())
                .explicitType(explicitType)
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
        return parse(header, prefix) != null;
    }
}

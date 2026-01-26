package com.e2eq.framework.model.persistent.imports;

/**
 * Header modifier suffixes for CSV import column headers.
 * These allow in-CSV metadata about field behavior.
 *
 * Example headers:
 * - "name*" - required field
 * - "description?" - optional field
 * - "createdAt~" - calculated field (generated if not provided)
 * - "refName#" - key field (used for duplicate detection)
 */
public enum HeaderModifier {
    /** No modifier - use default behavior */
    NONE(""),
    /** Required field - must have a value (suffix: *) */
    REQUIRED("*"),
    /** Optional field - null allowed (suffix: ?) */
    OPTIONAL("?"),
    /** Calculated field - generated if not provided (suffix: ~) */
    CALCULATED("~"),
    /** Key field - used for duplicate detection (suffix: #) */
    KEY("#");

    private final String suffix;

    HeaderModifier(String suffix) {
        this.suffix = suffix;
    }

    public String getSuffix() {
        return suffix;
    }

    /**
     * Parse a header modifier from its suffix character.
     * @param suffix the suffix character (*, ?, ~, #)
     * @return the corresponding HeaderModifier, or NONE if not recognized
     */
    public static HeaderModifier fromSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return NONE;
        }
        for (HeaderModifier modifier : values()) {
            if (modifier.suffix.equals(suffix)) {
                return modifier;
            }
        }
        return NONE;
    }

    /**
     * Check if a character is a valid header modifier suffix.
     * @param c the character to check
     * @return true if it's a valid modifier suffix
     */
    public static boolean isModifierChar(char c) {
        return c == '*' || c == '?' || c == '~' || c == '#';
    }
}

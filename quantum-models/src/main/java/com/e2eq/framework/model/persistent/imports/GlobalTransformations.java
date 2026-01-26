package com.e2eq.framework.model.persistent.imports;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Global transformations applied to all string fields during CSV import.
 * These are applied before column-specific transformations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@RegisterForReflection
public class GlobalTransformations {

    /**
     * Trim leading/trailing whitespace from all string fields.
     * Default: true
     */
    @Builder.Default
    private boolean trimStrings = true;

    /**
     * Convert empty strings to null.
     * Default: true
     */
    @Builder.Default
    private boolean emptyStringsToNull = true;

    /**
     * Unicode normalization form: NFC, NFD, NFKC, NFKD, or null for none.
     */
    private String unicodeNormalization;

    /**
     * Remove ASCII control characters (0x00-0x1F except tab/newline).
     * Default: false
     */
    @Builder.Default
    private boolean removeControlChars = false;

    /**
     * Maximum string length. Strings exceeding this are truncated.
     * Null means no limit.
     */
    private Integer maxStringLength;

    /**
     * Replace multiple consecutive whitespace with single space.
     * Default: false
     */
    @Builder.Default
    private boolean normalizeWhitespace = false;
}

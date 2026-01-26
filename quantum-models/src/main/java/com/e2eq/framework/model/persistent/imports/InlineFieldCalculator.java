package com.e2eq.framework.model.persistent.imports;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inline field calculator defined within an ImportProfile.
 * Supports simple expression-based calculations for computed fields.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@RegisterForReflection
public class InlineFieldCalculator {

    /**
     * Field name to calculate.
     */
    private String fieldName;

    /**
     * Calculation type.
     */
    private CalculationType type;

    /**
     * Static value (for STATIC type).
     */
    private String staticValue;

    /**
     * Source field (for COPY type).
     */
    private String sourceField;

    /**
     * Template with ${fieldName} placeholders (for TEMPLATE type).
     */
    private String template;

    /**
     * Calculation type options for inline field calculators.
     */
    public enum CalculationType {
        /** Use current timestamp */
        TIMESTAMP,
        /** Generate UUID */
        UUID,
        /** Use static value */
        STATIC,
        /** Copy from another field */
        COPY,
        /** Template with field placeholders */
        TEMPLATE
    }
}

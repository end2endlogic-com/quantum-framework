package com.e2eq.framework.annotations;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Validates that a String field contains a valid query filter expression
 * that references only existing fields in the target model class.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@Documented
public @interface ValidQueryFilter {
    
    String message() default "Invalid query filter: references non-existent fields";
    
    Class<?>[] groups() default {};
    
    Class<? extends Payload>[] payload() default {};
    
    /**
     * The model class to validate field references against.
     */
    Class<?> modelClass();
}

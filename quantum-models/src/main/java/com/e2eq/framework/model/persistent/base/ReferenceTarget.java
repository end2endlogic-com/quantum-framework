package com.e2eq.framework.model.persistent.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to declare the target model/class (or collection) for an EntityReference field.
 * v1: we use target() to determine collection name via the target class' @Entity value
 * or fall back to the class simple name when value is not provided.
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReferenceTarget {
    Class<?> target();
    String collection() default ""; // optional explicit collection override
}

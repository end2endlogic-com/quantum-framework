package com.e2eq.framework.annotations;

import java.lang.annotation.*;

/**
 * Annotation that maps a resource/model to a Functional Area and Functional Domain.
 * Can be applied at class level (for models and REST resources) or method level (for specific endpoints).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface FunctionalMapping {
    String area();
    String domain();
}

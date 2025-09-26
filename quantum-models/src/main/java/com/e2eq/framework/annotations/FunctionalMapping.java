package com.e2eq.framework.annotations;

import java.lang.annotation.*;

/**
 * Class-level annotation that maps a resource/model to a Functional Area and Functional Domain.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface FunctionalMapping {
    String area();
    String domain();
}

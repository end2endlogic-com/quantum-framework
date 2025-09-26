package com.e2eq.framework.annotations;

import java.lang.annotation.*;

/**
 * Method-level annotation to declare the functional action (e.g., CREATE, VIEW, UPDATE, DELETE).
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface FunctionalAction {
    String value();
}

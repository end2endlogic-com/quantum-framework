package com.e2eq.framework.securityrules;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;

/**
 * Optional annotation to indicate a custom method that computes labels
 * for an entity. The method should return either {@code Collection<String>},
 * {@code String[]} or a single {@code String}.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LabelSource {
    String method() default ""; // e.g., "computeLabels"
}

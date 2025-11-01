package com.e2eq.framework.securityrules;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional annotation to designate a field whose value contributes labels.
 * Field type may be {@code Collection<String>}, {@code String[]}, or {@code String}.
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface LabelField {
}

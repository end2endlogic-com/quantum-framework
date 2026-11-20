package com.e2eq.framework.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource parameter (a {@code @PathParam} or {@code @QueryParam}, or a field of a
 * {@code @BeanParam}) whose value is a realm refName although its name does not say so, for example
 * {@code {refName}} on a realm directory resource. The request boundary admits it before the
 * resource runs, as it admits a parameter named {@code realm}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
public @interface RealmName {

    /**
     * {@code true} (the default): the value must name a registered realm. {@code false}: the value
     * names a realm being registered, so only its shape and reserved names are checked.
     */
    boolean registered() default true;
}

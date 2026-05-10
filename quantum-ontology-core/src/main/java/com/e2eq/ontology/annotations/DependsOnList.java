package com.e2eq.ontology.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Container annotation for repeatable {@link DependsOn}. */
@Retention(RUNTIME)
@Target(TYPE)
public @interface DependsOnList {
    DependsOn[] value();
}

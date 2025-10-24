package com.e2eq.ontology.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a Morphia entity class as an ontology class and optionally customizes its id and parents.
 * Default id is the simple class name.
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface OntologyClass {
    String id() default "";
    String[] subClassOf() default {};
}

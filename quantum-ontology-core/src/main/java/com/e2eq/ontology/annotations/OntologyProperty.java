package com.e2eq.ontology.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a field or getter as an ontology property (edge) and declares optional traits.
 */
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface OntologyProperty {
    String id() default "";                 // default: field name or method-derived name
    String[] subPropertyOf() default {};
    String inverseOf() default "";          // declare inverse property name
    boolean transitive() default false;
    boolean symmetric() default false;
    boolean functional() default false;      // inferred true for single-valued unless overridden
    String domain() default "";             // override declaring class id
    String range() default "";              // override inferred target class id
    String[] aliases() default {};           // optional synonyms/aliases for this property
}

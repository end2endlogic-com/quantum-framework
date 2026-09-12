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

    // Relationship/edge semantics (optional)
    String ref() default "";                // target ontology type/class id (overrides range if set)
    RelationType relation() default RelationType.NONE; // multiplicity/cardinality
    String edgeType() default "";          // edge label/name used for traversal/materialization
    String inverseOfEdge() default "";     // optional pointer to inverse edge label
    boolean materializeEdge() default true; // allow opting out of edge materialization while keeping ref

    // Cascading (optional and opt-in)
    CascadeType[] cascade() default { CascadeType.NONE };
    int cascadeDepth() default 1;           // safety cap for recursive deletes

    /**
     * Expected validity half-life of this property's value, in seconds.
     *
     * <p>Timeliness is a ratio -- {@code age(observation) / half-life(property)} -- rather than a
     * flat freshness bound, because how long a value stays true is a fact about the property and
     * not about the source that supplied it. A registry extract is effectively permanent on a
     * legal name and stale within weeks on a registration status; one bound over the source
     * cannot express both.
     *
     * <p>Declaring it here puts the judgement in the vocabulary, where it is reviewed once and
     * inherited by every binding that resolves the property, rather than being re-guessed per
     * source. The declared value is a prior: it is better learned from successive observations
     * than assigned, and a measured half-life should override it visibly rather than silently.
     *
     * <p>Defaults to {@link #HALF_LIFE_UNSPECIFIED}, which means "no judgement recorded" and is
     * deliberately not a number -- a fabricated default would be indistinguishable from a
     * reviewed one, and a consumer must be able to tell those apart. A property that leaves this
     * unset writes no metadata key, so existing canonical TBox hashes are unaffected.
     */
    long expectedValidityHalfLifeSeconds() default HALF_LIFE_UNSPECIFIED;

    /** Sentinel for {@link #expectedValidityHalfLifeSeconds()}: no half-life has been declared. */
    long HALF_LIFE_UNSPECIFIED = -1L;

    /** Metadata key carrying a declared half-life into {@code PropertyDef.metadata()}. */
    String HALF_LIFE_METADATA_KEY = "expected_validity_half_life_seconds";
}

package com.e2eq.ontology.annotations;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares that a {@code ComputedEdgeProvider} reads from another entity type
 * during its computation.
 *
 * <p>Two effects:</p>
 * <ul>
 *   <li>The annotated type is auto-registered as a dependency type, so changes
 *       to it can trigger recomputation. This populates
 *       {@code ComputedEdgeProvider.getDependencyTypes()} when the method is
 *       not overridden.</li>
 *   <li>The optional {@link #via} field on the source entity, combined with
 *       {@link #expand}, gives enough information for a future inverse-query
 *       resolver to derive {@code getAffectedSourceIds()} without each provider
 *       reimplementing it. Today the framework uses this metadata for
 *       startup-time validation: providers declaring dependencies but missing
 *       both an override and a usable {@code @DependsOn} get a WARN.</li>
 * </ul>
 */
@Retention(RUNTIME)
@Target(TYPE)
@Repeatable(DependsOnList.class)
public @interface DependsOn {

    /** Entity type this provider reads. */
    Class<?> type();

    /**
     * Optional field name on the source entity that references {@link #type}.
     * Used by the inverse-query resolver. Empty means "no inverse-query hint";
     * the provider must override {@code getAffectedSourceIds} itself.
     */
    String via() default "";

    /** Hierarchy expansion direction for inverse-query resolution. */
    Expand expand() default Expand.NONE;

    enum Expand {
        /** No traversal beyond direct references. */
        NONE,
        /** Walk parent links from the changed entity. */
        ANCESTORS,
        /** Walk child links from the changed entity. */
        DESCENDANTS
    }
}

package com.e2eq.framework.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity type for tool auto-generation. When the ToolAutoGenerator runs
 * (e.g. from root type names or by scanning annotated types), it can produce
 * search_*, get_*, create_*, update_*, delete_*, count_* tools for this type.
 * <p>
 * Use on entity classes that correspond to a root type in the type registry.
 * The generator uses the type's simple name (or singularName) for refNames.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolGeneration {

    /**
     * Singular name for tool refs (e.g. "Order" → search_Order, get_Order).
     * Defaults to the annotated class's simple name.
     */
    String singularName() default "";

    /**
     * Plural name for descriptions (e.g. "Orders"). Optional.
     */
    String pluralName() default "";

    /**
     * Short description for generated tool descriptions.
     */
    String description() default "";

    /**
     * Category for generated tools (e.g. "quantum.crud").
     */
    String category() default "quantum.crud";

    /**
     * Operations to exclude from generation (e.g. "delete" to skip delete_*).
     * Allowed: search, get, create, update, delete, count.
     */
    String[] excludeOperations() default {};

    /**
     * Optional tags for generated tools.
     */
    String[] tags() default {};

    /**
     * Optional searchable field names for search_* tool description hints.
     */
    String[] searchableFields() default {};
}

package com.e2eq.ontology.annotations;

/**
 * Cardinality of a relationship represented by an ontology property.
 */
public enum RelationType {
    NONE,
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE,
    MANY_TO_MANY
}

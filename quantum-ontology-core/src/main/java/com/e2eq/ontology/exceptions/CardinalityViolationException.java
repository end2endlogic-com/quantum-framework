package com.e2eq.ontology.exceptions;

/**
 * Thrown when an ontology cardinality constraint is violated.
 * <p>
 * This exception is thrown when attempting to create an edge that would violate
 * a functional property constraint (e.g., ONE_TO_ONE or MANY_TO_ONE relationships
 * where only one target is allowed per source).
 * </p>
 */
public class CardinalityViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String predicate;
    private final String sourceId;
    private final String sourceType;
    private final String existingTargetId;
    private final String attemptedTargetId;

    public CardinalityViolationException(String message) {
        super(message);
        this.predicate = null;
        this.sourceId = null;
        this.sourceType = null;
        this.existingTargetId = null;
        this.attemptedTargetId = null;
    }

    public CardinalityViolationException(String message, Throwable cause) {
        super(message, cause);
        this.predicate = null;
        this.sourceId = null;
        this.sourceType = null;
        this.existingTargetId = null;
        this.attemptedTargetId = null;
    }

    public CardinalityViolationException(String predicate, String sourceId, String sourceType,
                                         String existingTargetId, String attemptedTargetId) {
        super(buildMessage(predicate, sourceId, sourceType, existingTargetId, attemptedTargetId));
        this.predicate = predicate;
        this.sourceId = sourceId;
        this.sourceType = sourceType;
        this.existingTargetId = existingTargetId;
        this.attemptedTargetId = attemptedTargetId;
    }

    public CardinalityViolationException(String predicate, String sourceId, String sourceType,
                                         String existingTargetId, String attemptedTargetId, Throwable cause) {
        super(buildMessage(predicate, sourceId, sourceType, existingTargetId, attemptedTargetId), cause);
        this.predicate = predicate;
        this.sourceId = sourceId;
        this.sourceType = sourceType;
        this.existingTargetId = existingTargetId;
        this.attemptedTargetId = attemptedTargetId;
    }

    private static String buildMessage(String predicate, String sourceId, String sourceType,
                                        String existingTargetId, String attemptedTargetId) {
        return String.format(
            "Functional property constraint violated: property '%s' is functional (ONE_TO_ONE or MANY_TO_ONE). " +
            "Source %s[%s] already has edge to '%s', cannot create edge to '%s'",
            predicate, sourceType != null ? sourceType : "entity", sourceId, existingTargetId, attemptedTargetId
        );
    }

    /**
     * The predicate/property that has the functional constraint.
     */
    public String getPredicate() {
        return predicate;
    }

    /**
     * The source entity ID that already has an edge.
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * The type of the source entity.
     */
    public String getSourceType() {
        return sourceType;
    }

    /**
     * The existing target ID that the source already points to.
     */
    public String getExistingTargetId() {
        return existingTargetId;
    }

    /**
     * The target ID that was attempted to be added (which would violate the constraint).
     */
    public String getAttemptedTargetId() {
        return attemptedTargetId;
    }
}

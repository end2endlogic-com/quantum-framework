package com.e2eq.framework.model.general;

/**
 * Simple event object used to capture auditing information.
 */
public class AuditEvent {
    private final Object entity;
    private final String userId;

    /**
     * Creates a new audit event representing a change to {@code entity} by the given user.
     */
    public AuditEvent(Object entity, String userId) {
        this.entity = entity;
        this.userId = userId;
    }

    /**
     * @return the entity associated with this event
     */
    public Object getEntity() {
        return entity;
    }

    /**
     * @return identifier of the user triggering the event
     */
    public String getUserId() {
        return userId;
    }
}


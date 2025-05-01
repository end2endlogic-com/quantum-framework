package com.e2eq.framework.model.general;

public class AuditEvent {
    private final Object entity;
    private final String userId;

    public AuditEvent(Object entity, String userId) {
        this.entity = entity;
        this.userId = userId;
    }

    public Object getEntity() {
        return entity;
    }

    public String getUserId() {
        return userId;
    }
}
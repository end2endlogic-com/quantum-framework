package com.e2eq.framework.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ControlPlaneActiveStatus {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE"),
    DELETED("DELETED");

    private final String value;

    ControlPlaneActiveStatus(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static ControlPlaneActiveStatus fromValue(String value) {
        for (ControlPlaneActiveStatus candidate : values()) {
            if (candidate.value.equals(value)) return candidate;
        }
        throw new IllegalArgumentException("Unknown ControlPlaneActiveStatus value: " + value);
    }
}

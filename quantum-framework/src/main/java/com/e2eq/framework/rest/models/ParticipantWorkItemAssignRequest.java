package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

/** Assigns business responsibility independently of queue claiming. */
@RegisterForReflection
public class ParticipantWorkItemAssignRequest {
    @NotNull
    public Long expectedVersion;
    public String assigneeRef;
}

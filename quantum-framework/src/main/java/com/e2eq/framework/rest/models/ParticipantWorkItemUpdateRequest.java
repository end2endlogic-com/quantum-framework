package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Date;

/** Revision-checked business edit for a participant-owned TODO. */
@RegisterForReflection
public class ParticipantWorkItemUpdateRequest {
    @NotNull
    public Long expectedVersion;
    @NotBlank
    public String summary;
    public String details;
    public Date dueDate;
}

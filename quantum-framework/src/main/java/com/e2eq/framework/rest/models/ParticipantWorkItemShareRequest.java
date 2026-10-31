package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.tasks.TaskGrant;
import com.e2eq.framework.model.persistent.tasks.WorkItemVisibility;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Replaces the explicit audience of a participant-owned TODO. */
@RegisterForReflection
public class ParticipantWorkItemShareRequest {
    @NotNull
    public Long expectedVersion;
    @NotNull
    public WorkItemVisibility visibility;
    public List<TaskGrant> grants = new ArrayList<>();
}

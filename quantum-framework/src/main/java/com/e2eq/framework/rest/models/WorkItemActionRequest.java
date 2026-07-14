package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.persistent.tasks.WorkerType;
import com.e2eq.framework.model.persistent.tasks.TaskPayload;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Revision-checked input for claim, lifecycle, and heartbeat operations.
 */
@RegisterForReflection
public class WorkItemActionRequest {
    @NotNull
    public Long expectedVersion;

    /**
     * Decision assessment snapshot the worker reviewed. Completion fails closed
     * when it no longer matches the task subject's assessment version.
     */
    public String expectedAssessmentVersion;

    public WorkerType workerType;

    @Min(30)
    public Long leaseSeconds;

    public String result;

    public TaskPayload resultPayload;
}

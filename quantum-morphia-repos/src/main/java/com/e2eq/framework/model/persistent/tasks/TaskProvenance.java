package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workflow coordinates needed to resume the business process after completion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class TaskProvenance {
    private String executionRef;
    private String definitionRef;
    private String stepKey;
    /** Revision of the workflow-runtime task at dispatch time, used for completion CAS. */
    private Long workflowTaskRevision;
    private String signalType;
    private String signalOnComplete;
    private String createdBy;
}

package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Current queue and claim ownership for a work item.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class TaskAssignment {
    private String queueRef;
    /** Business responsibility; independent of transient queue claiming. */
    private String assigneeRef;
    private String assignedBy;
    private Date assignedAt;
    private String claimedBy;
    private WorkerType claimedWorkerType;
    private Date claimedAt;
    private Date heartbeatAt;
    private Date leaseUntil;
}

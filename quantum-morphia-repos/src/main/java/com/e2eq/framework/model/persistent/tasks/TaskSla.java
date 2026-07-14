package com.e2eq.framework.model.persistent.tasks;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Measurable service-level targets attached to a work item.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class TaskSla {
    private String policyRef;
    private Date warningAt;
    private Date targetAt;
    private Date breachedAt;
}

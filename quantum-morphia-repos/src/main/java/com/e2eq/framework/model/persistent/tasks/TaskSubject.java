package com.e2eq.framework.model.persistent.tasks;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stable business references and UI deep link for the decision context a task advances.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
public class TaskSubject {
    private String workbookRef;
    private String decisionTypeRef;
    private String decisionCaseRef;
    private String assessmentVersion;
    private String navigationRoute;
}

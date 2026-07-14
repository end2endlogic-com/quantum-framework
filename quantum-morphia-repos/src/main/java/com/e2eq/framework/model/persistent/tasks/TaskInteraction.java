package com.e2eq.framework.model.persistent.tasks;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Declares which UI or external experience captures the task result and where
 * the validated response is integrated in the decision context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class TaskInteraction {
    private TaskInteractionType type;
    private String experienceRef;
    private String responseMappingRef;
    private String targetContextPath;
}

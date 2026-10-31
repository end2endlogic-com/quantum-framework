package com.e2eq.framework.model.persistent.tasks;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The governed input, result, rule, and action contract for a work item.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class TaskContract {
    private String inputSchemaRef;
    private String resultSchemaRef;
    private String ruleSetRef;

    @Builder.Default
    private List<String> allowedActions = new ArrayList<>();
}

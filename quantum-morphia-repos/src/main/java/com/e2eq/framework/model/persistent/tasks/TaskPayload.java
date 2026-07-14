package com.e2eq.framework.model.persistent.tasks;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schema-identified payload envelope used at task/orchestration boundaries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class TaskPayload {
    private String schemaRef;

    @Builder.Default
    private Map<String, Object> data = new LinkedHashMap<>();

    private Date capturedAt;
    private String capturedBy;
    private String evidenceRef;
}

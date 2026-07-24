package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class TenantProvisioningWorkflowResponse {
    private String id;
    private String refName;
    private String displayName;
    private String description;
    private boolean activeStatus;
    private int workflowVersion;
    private String completionMessage;
    private String workflowDefinitionJson;
    @Builder.Default
    private List<TenantProvisioningWorkflowStepResponse> steps = new ArrayList<>();
}

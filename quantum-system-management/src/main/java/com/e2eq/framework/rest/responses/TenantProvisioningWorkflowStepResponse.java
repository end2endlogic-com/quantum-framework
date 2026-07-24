package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TenantProvisioningWorkflowStepResponse {
    private String key;
    private String label;
    private String type;
    private String description;
    private boolean required;
}

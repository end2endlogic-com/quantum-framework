package com.e2eq.framework.rest.requests;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode
@SuperBuilder
@ToString
@NoArgsConstructor
public class TenantProvisioningWorkflowRequest {
    protected String refName;
    protected String displayName;
    protected String description;
    protected Boolean activeStatus;
    protected String completionMessage;
    protected String workflowDefinitionJson;
}

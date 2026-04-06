package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class TenantOnboardingWorkflowResponse {
    private String id;
    private String refName;
    private String displayName;
    private String description;
    private boolean activeStatus;
    private boolean inviteRequired;
    private boolean registrationRequired;
    private boolean surveyRequired;
    private boolean adminApprovalRequired;
    private boolean autoAssignSurveyOnInvite;
    private String defaultSurveyRefName;
    private String completionMessage;
    private String workflowDefinitionJson;
    @Builder.Default
    private List<TenantOnboardingWorkflowStepResponse> steps = new ArrayList<>();
}

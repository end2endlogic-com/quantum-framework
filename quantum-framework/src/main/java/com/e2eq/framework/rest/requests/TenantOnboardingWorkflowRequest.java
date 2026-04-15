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
public class TenantOnboardingWorkflowRequest {
    protected String refName;
    protected String displayName;
    protected String description;
    protected Boolean activeStatus;
    protected Boolean inviteRequired;
    protected Boolean registrationRequired;
    protected Boolean surveyRequired;
    protected Boolean adminApprovalRequired;
    protected Boolean autoAssignSurveyOnInvite;
    protected String defaultSurveyRefName;
    protected String defaultInviteEmailTemplateKey;
    protected String completionMessage;
    protected String workflowDefinitionJson;
}

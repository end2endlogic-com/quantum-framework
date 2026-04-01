package com.e2eq.framework.service.onboarding;

import com.e2eq.framework.model.persistent.morphia.TenantOnboardingWorkflowRepo;
import com.e2eq.framework.model.security.TenantOnboardingWorkflow;
import com.e2eq.framework.rest.requests.TenantOnboardingWorkflowRequest;
import com.e2eq.framework.rest.responses.TenantOnboardingWorkflowResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TenantOnboardingWorkflowService {

    @Inject
    TenantOnboardingWorkflowRepo workflowRepo;

    @Inject
    TenantOnboardingWorkflowDefaults defaults;

    public TenantOnboardingWorkflowResponse getCurrentWorkflow(String realm) {
        TenantOnboardingWorkflow workflow = workflowRepo.findActive(realm)
            .or(() -> workflowRepo.findDefault(realm))
            .orElseGet(() -> createDefaultWorkflow(realm));
        return toResponse(realm, workflow);
    }

    public TenantOnboardingWorkflowResponse saveCurrentWorkflow(String realm, TenantOnboardingWorkflowRequest request) {
        TenantOnboardingWorkflow workflow = workflowRepo.findDefault(realm)
            .orElseGet(() -> createDefaultWorkflow(realm));

        workflow.setRefName(nonBlank(request.getRefName(), workflow.getRefName(), TenantOnboardingWorkflow.DEFAULT_REF_NAME));
        workflow.setDisplayName(nonBlank(request.getDisplayName(), workflow.getDisplayName(), "Tenant User Onboarding"));
        workflow.setDescription(nonBlank(request.getDescription(), workflow.getDescription(),
            "Review, approve, and activate tenant users through the standard onboarding journey."));
        workflow.setWorkflowEnabled(request.getActiveStatus() != null ? request.getActiveStatus() : workflow.isWorkflowEnabled());
        workflow.setInviteRequired(request.getInviteRequired() != null ? request.getInviteRequired() : workflow.isInviteRequired());
        workflow.setRegistrationRequired(request.getRegistrationRequired() != null ? request.getRegistrationRequired() : workflow.isRegistrationRequired());
        workflow.setSurveyRequired(request.getSurveyRequired() != null ? request.getSurveyRequired() : workflow.isSurveyRequired());
        workflow.setAdminApprovalRequired(request.getAdminApprovalRequired() != null ? request.getAdminApprovalRequired() : workflow.isAdminApprovalRequired());
        workflow.setAutoAssignSurveyOnInvite(request.getAutoAssignSurveyOnInvite() != null ? request.getAutoAssignSurveyOnInvite() : workflow.isAutoAssignSurveyOnInvite());
        workflow.setDefaultSurveyRefName(defaults.normalizeSurveyRefName(nonBlank(
            request.getDefaultSurveyRefName(),
            workflow.getDefaultSurveyRefName(),
            TenantOnboardingWorkflow.DEFAULT_SURVEY_REF_NAME
        )));
        workflow.setCompletionMessage(nonBlank(request.getCompletionMessage(), workflow.getCompletionMessage(),
            "Your tenant onboarding is complete. You can now sign in and begin using the workspace."));

        String workflowDefinitionJson = request.getWorkflowDefinitionJson();
        if (workflowDefinitionJson == null || workflowDefinitionJson.isBlank()) {
            workflowDefinitionJson = defaults.buildDefinitionJson(
                workflow.getDefaultSurveyRefName(),
                workflow.isInviteRequired(),
                workflow.isRegistrationRequired(),
                workflow.isSurveyRequired(),
                workflow.isAdminApprovalRequired()
            );
        }
        workflow.setWorkflowDefinitionJson(workflowDefinitionJson);

        workflow = workflowRepo.save(realm, workflow);
        return toResponse(realm, workflow);
    }

    private TenantOnboardingWorkflow createDefaultWorkflow(String realm) {
        TenantOnboardingWorkflow workflow = TenantOnboardingWorkflow.builder()
            .refName(TenantOnboardingWorkflow.DEFAULT_REF_NAME)
            .displayName("Tenant User Onboarding")
            .description("Review, approve, and activate tenant users through the standard onboarding journey.")
            .workflowEnabled(true)
            .inviteRequired(true)
            .registrationRequired(true)
            .surveyRequired(true)
            .adminApprovalRequired(true)
            .autoAssignSurveyOnInvite(true)
            .defaultSurveyRefName(TenantOnboardingWorkflow.DEFAULT_SURVEY_REF_NAME)
            .completionMessage("Your tenant onboarding is complete. You can now sign in and begin using the workspace.")
            .workflowDefinitionJson(defaults.buildDefinitionJson(
                TenantOnboardingWorkflow.DEFAULT_SURVEY_REF_NAME,
                true,
                true,
                true,
                true
            ))
            .build();
        return workflowRepo.save(realm, workflow);
    }

    private TenantOnboardingWorkflowResponse toResponse(String realm, TenantOnboardingWorkflow workflow) {
        String effectiveSurveyRef = defaults.normalizeSurveyRefName(workflow.getDefaultSurveyRefName());
        String effectiveDefinitionJson = workflow.getWorkflowDefinitionJson();
        if (effectiveDefinitionJson == null || effectiveDefinitionJson.isBlank()) {
            effectiveDefinitionJson = defaults.buildDefinitionJson(
                effectiveSurveyRef,
                workflow.isInviteRequired(),
                workflow.isRegistrationRequired(),
                workflow.isSurveyRequired(),
                workflow.isAdminApprovalRequired()
            );
        }
        return TenantOnboardingWorkflowResponse.builder()
            .id(workflow.getId() != null ? workflow.getId().toHexString() : null)
            .refName(workflow.getRefName())
            .displayName(workflow.getDisplayName())
            .description(workflow.getDescription())
            .activeStatus(workflow.isWorkflowEnabled())
            .inviteRequired(workflow.isInviteRequired())
            .registrationRequired(workflow.isRegistrationRequired())
            .surveyRequired(workflow.isSurveyRequired())
            .adminApprovalRequired(workflow.isAdminApprovalRequired())
            .autoAssignSurveyOnInvite(workflow.isAutoAssignSurveyOnInvite())
            .defaultSurveyRefName(effectiveSurveyRef)
            .completionMessage(workflow.getCompletionMessage())
            .workflowDefinitionJson(effectiveDefinitionJson)
            .steps(defaults.parseSteps(
                effectiveDefinitionJson,
                effectiveSurveyRef,
                workflow.isInviteRequired(),
                workflow.isRegistrationRequired(),
                workflow.isSurveyRequired(),
                workflow.isAdminApprovalRequired()
            ))
            .build();
    }

    private String nonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

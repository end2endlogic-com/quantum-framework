package com.e2eq.framework.service.onboarding;

import com.e2eq.framework.model.persistent.morphia.CompletionTaskGroupRepo;
import com.e2eq.framework.model.persistent.morphia.CompletionTaskRepo;
import com.e2eq.framework.model.persistent.morphia.TenantOnboardingWorkflowRepo;
import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup;
import com.e2eq.framework.model.security.TenantOnboardingWorkflow;
import com.e2eq.framework.rest.requests.TenantOnboardingRunStartRequest;
import com.e2eq.framework.rest.requests.TenantOnboardingWorkflowRequest;
import com.e2eq.framework.rest.responses.TenantOnboardingRunResponse;
import com.e2eq.framework.rest.responses.TenantOnboardingRunTaskResponse;
import com.e2eq.framework.rest.responses.TenantOnboardingWorkflowStepResponse;
import com.e2eq.framework.rest.responses.TenantOnboardingWorkflowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@DefaultBean
public class TenantOnboardingWorkflowService implements TenantOnboardingFlowService {

    @Inject
    TenantOnboardingWorkflowRepo workflowRepo;

    @Inject
    TenantOnboardingWorkflowDefaults defaults;

    @Inject
    CompletionTaskGroupRepo completionTaskGroupRepo;

    @Inject
    CompletionTaskRepo completionTaskRepo;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public TenantOnboardingWorkflowResponse getCurrentWorkflow(String realm) {
        TenantOnboardingWorkflow workflow = workflowRepo.findActive(realm)
            .or(() -> workflowRepo.findDefault(realm))
            .orElseGet(() -> createDefaultWorkflow(realm));
        return toResponse(realm, workflow);
    }

    @Override
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
        workflow.setDefaultInviteEmailTemplateKey(nonBlank(
            request.getDefaultInviteEmailTemplateKey(),
            workflow.getDefaultInviteEmailTemplateKey(),
            TenantOnboardingWorkflow.DEFAULT_INVITE_EMAIL_TEMPLATE_KEY
        ));
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

    @Override
    public TenantOnboardingRunResponse startWorkflow(String realm, TenantOnboardingRunStartRequest request) {
        TenantOnboardingWorkflowResponse workflow = getCurrentWorkflow(realm);
        String runRef = nonBlank(request != null ? request.getRunRef() : null, "onboarding-" + UUID.randomUUID().toString().replace("-", ""));

        CompletionTaskGroup group = CompletionTaskGroup.builder()
            .refName(runRef)
            .displayName(nonBlank(
                request != null ? request.getDisplayName() : null,
                workflow.getDisplayName(),
                "Tenant Onboarding"))
            .description(nonBlank(
                request != null ? request.getDescription() : null,
                workflow.getDescription(),
                "Tenant onboarding execution"))
            .build();
        group = completionTaskGroupRepo.createGroup(realm, group);
        completionTaskGroupRepo.updateStatus(realm, group.getId().toHexString(), CompletionTaskGroup.Status.RUNNING);

        List<CompletionTask> tasks = new ArrayList<>();
        for (TenantOnboardingWorkflowStepResponse step : workflow.getSteps()) {
            CompletionTask task = CompletionTask.builder()
                .refName(runRef + "-" + step.getKey())
                .displayName(step.getLabel())
                .details(serializeTaskDetails(step, request))
                .build();
            tasks.add(completionTaskRepo.createTask(realm, task, group.getId().toHexString()));
        }

        return toRunResponse(realm, group, workflow, tasks);
    }

    @Override
    public Optional<TenantOnboardingRunResponse> getWorkflowRun(String realm, String runRef) {
        Optional<CompletionTaskGroup> group = completionTaskGroupRepo.findByRefName(realm, runRef);
        if (group.isEmpty()) {
            return Optional.empty();
        }

        TenantOnboardingWorkflowResponse workflow = getCurrentWorkflow(realm);
        List<CompletionTask> tasks = completionTaskRepo.listByGroup(realm, group.get().getId().toHexString());
        return Optional.of(toRunResponse(realm, group.get(), workflow, tasks));
    }

    protected TenantOnboardingWorkflow createDefaultWorkflow(String realm) {
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
            .defaultInviteEmailTemplateKey(TenantOnboardingWorkflow.DEFAULT_INVITE_EMAIL_TEMPLATE_KEY)
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

    protected TenantOnboardingWorkflowResponse toResponse(String realm, TenantOnboardingWorkflow workflow) {
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
            .defaultInviteEmailTemplateKey(nonBlank(
                workflow.getDefaultInviteEmailTemplateKey(),
                TenantOnboardingWorkflow.DEFAULT_INVITE_EMAIL_TEMPLATE_KEY
            ))
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

    protected TenantOnboardingRunResponse toRunResponse(
        String realm,
        CompletionTaskGroup group,
        TenantOnboardingWorkflowResponse workflow,
        List<CompletionTask> tasks
    ) {
        return TenantOnboardingRunResponse.builder()
            .realmId(realm)
            .groupId(group.getId() != null ? group.getId().toHexString() : null)
            .runRef(group.getRefName())
            .workflowRefName(workflow.getRefName())
            .workflowDisplayName(workflow.getDisplayName())
            .status(deriveRunStatus(group, tasks))
            .completionMessage(workflow.getCompletionMessage())
            .createdDate(group.getCreatedDate())
            .completedDate(group.getCompletedDate())
            .tasks(tasks.stream().map(this::toTaskResponse).toList())
            .build();
    }

    protected TenantOnboardingRunTaskResponse toTaskResponse(CompletionTask task) {
        return TenantOnboardingRunTaskResponse.builder()
            .id(task.getId() != null ? task.getId().toHexString() : null)
            .refName(task.getRefName())
            .displayName(task.getDisplayName())
            .status(task.getStatus() != null ? task.getStatus().name() : null)
            .details(task.getDetails())
            .result(task.getResult())
            .createdDate(task.getCreatedDate())
            .completedDate(task.getCompletedDate())
            .build();
    }

    protected String deriveRunStatus(CompletionTaskGroup group, List<CompletionTask> tasks) {
        if (tasks.stream().anyMatch(task -> task.getStatus() == CompletionTask.Status.FAILED)) {
            return "FAILED";
        }
        if (!tasks.isEmpty() && tasks.stream().allMatch(task -> task.getStatus() == CompletionTask.Status.SUCCESS)) {
            return "COMPLETED";
        }
        if (group.getStatus() == CompletionTaskGroup.Status.COMPLETE) {
            return "COMPLETED";
        }
        if (group.getStatus() == CompletionTaskGroup.Status.RUNNING) {
            return "RUNNING";
        }
        return "PENDING";
    }

    protected String serializeTaskDetails(TenantOnboardingWorkflowStepResponse step, TenantOnboardingRunStartRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepKey", step.getKey());
        payload.put("stepType", step.getType());
        payload.put("required", step.isRequired());
        payload.put("description", step.getDescription());
        if (step.getSurveyRefName() != null) {
            payload.put("surveyRefName", step.getSurveyRefName());
        }
        if (request != null) {
            putIfPresent(payload, "actorRef", request.getActorRef());
            putIfPresent(payload, "userId", request.getUserId());
            putIfPresent(payload, "subjectId", request.getSubjectId());
            putIfPresent(payload, "email", request.getEmail());
            if (request.getMetadata() != null) {
                payload.put("metadata", request.getMetadata());
            }
        }
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (Exception e) {
            return payload.toString();
        }
    }

    protected void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value.trim());
        }
    }

    protected String nonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

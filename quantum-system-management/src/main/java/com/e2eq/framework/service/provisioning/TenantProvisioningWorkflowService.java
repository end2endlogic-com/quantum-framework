package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.model.persistent.morphia.TenantProvisioningWorkflowRepo;
import com.e2eq.framework.model.security.TenantProvisioningWorkflow;
import com.e2eq.framework.rest.requests.TenantProvisioningWorkflowRequest;
import com.e2eq.framework.rest.responses.TenantProvisioningWorkflowResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TenantProvisioningWorkflowService {

    @Inject
    TenantProvisioningWorkflowRepo workflowRepo;

    @Inject
    TenantProvisioningWorkflowDefaults defaults;

    public TenantProvisioningWorkflow resolveCurrentWorkflow(String realm) {
        return workflowRepo.findActive(realm)
            .or(() -> workflowRepo.findDefault(realm))
            .orElseGet(() -> createDefaultWorkflow(realm));
    }

    public TenantProvisioningWorkflowResponse getCurrentWorkflow(String realm) {
        return toResponse(resolveCurrentWorkflow(realm));
    }

    public TenantProvisioningWorkflowResponse saveCurrentWorkflow(String realm, TenantProvisioningWorkflowRequest request) {
        TenantProvisioningWorkflow workflow = workflowRepo.findDefault(realm)
            .orElseGet(() -> createDefaultWorkflow(realm));

        workflow.setRefName(nonBlank(request.getRefName(), workflow.getRefName(), TenantProvisioningWorkflow.DEFAULT_REF_NAME));
        workflow.setDisplayName(nonBlank(request.getDisplayName(), workflow.getDisplayName(), "Tenant Provisioning"));
        workflow.setDescription(nonBlank(request.getDescription(), workflow.getDescription(),
            "Review and control the durable workflow that provisions tenant realms, identities, and bootstrap packs."));
        workflow.setWorkflowEnabled(request.getActiveStatus() != null ? request.getActiveStatus() : workflow.isWorkflowEnabled());
        workflow.setCompletionMessage(nonBlank(request.getCompletionMessage(), workflow.getCompletionMessage(),
            "Tenant provisioning completed successfully."));

        String workflowDefinitionJson = request.getWorkflowDefinitionJson();
        if (workflowDefinitionJson == null || workflowDefinitionJson.isBlank()) {
            workflowDefinitionJson = defaults.buildDefinitionJson();
        }
        defaults.validateSteps(workflowDefinitionJson);
        workflow.setWorkflowDefinitionJson(workflowDefinitionJson);
        workflow.setWorkflowVersion(Math.max(1, workflow.getWorkflowVersion()) + (workflow.getId() == null ? 0 : 1));

        workflow = workflowRepo.save(realm, workflow);
        return toResponse(workflow);
    }

    private TenantProvisioningWorkflow createDefaultWorkflow(String realm) {
        TenantProvisioningWorkflow workflow = TenantProvisioningWorkflow.builder()
            .refName(TenantProvisioningWorkflow.DEFAULT_REF_NAME)
            .displayName("Tenant Provisioning")
            .description("Review and control the durable workflow that provisions tenant realms, identities, and bootstrap packs.")
            .workflowEnabled(true)
            .workflowVersion(1)
            .completionMessage("Tenant provisioning completed successfully.")
            .workflowDefinitionJson(defaults.buildDefinitionJson())
            .build();
        return workflowRepo.save(realm, workflow);
    }

    private TenantProvisioningWorkflowResponse toResponse(TenantProvisioningWorkflow workflow) {
        String effectiveDefinition = workflow.getWorkflowDefinitionJson();
        if (effectiveDefinition == null || effectiveDefinition.isBlank()) {
            effectiveDefinition = defaults.buildDefinitionJson();
        }
        return TenantProvisioningWorkflowResponse.builder()
            .id(workflow.getId() != null ? workflow.getId().toHexString() : null)
            .refName(workflow.getRefName())
            .displayName(workflow.getDisplayName())
            .description(workflow.getDescription())
            .activeStatus(workflow.isWorkflowEnabled())
            .workflowVersion(workflow.getWorkflowVersion())
            .completionMessage(workflow.getCompletionMessage())
            .workflowDefinitionJson(effectiveDefinition)
            .steps(defaults.parseSteps(effectiveDefinition))
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

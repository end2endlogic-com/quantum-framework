package com.e2eq.framework.service.onboarding;

import com.e2eq.framework.model.security.TenantOnboardingWorkflow;
import com.e2eq.framework.rest.responses.TenantOnboardingWorkflowStepResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
public class TenantOnboardingWorkflowDefaults {

    @Inject
    ObjectMapper objectMapper;

    public String buildDefinitionJson(
        String defaultSurveyRefName,
        boolean inviteRequired,
        boolean registrationRequired,
        boolean surveyRequired,
        boolean adminApprovalRequired
    ) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (inviteRequired) {
            steps.add(step("invite", "Invite User", "access_invite", true,
                "Invite the user into the tenant and establish the initial access scope."));
        }
        if (registrationRequired) {
            steps.add(step("registration", "Registration", "registration_request", true,
                "Capture the user and company registration details for this tenant."));
        }
        if (surveyRequired) {
            Map<String, Object> surveyStep = step("survey", "Onboarding Survey", "survey", true,
                "Collect the tenant's onboarding information and readiness details.");
            surveyStep.put("surveyRefName", normalizeSurveyRefName(defaultSurveyRefName));
            steps.add(surveyStep);
        }
        if (adminApprovalRequired) {
            steps.add(step("approval", "Admin Approval", "admin_approval", true,
                "Review the onboarding submission and approve access for the tenant."));
        }
        steps.add(step("activation", "Account Activation", "account_activation", true,
            "Finalize the onboarding flow and make the user's tenant access available."));

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("kind", "tenant-onboarding");
        definition.put("version", 1);
        definition.put("steps", steps);
        try {
            return objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(definition);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build default tenant onboarding workflow definition.", e);
        }
    }

    public List<TenantOnboardingWorkflowStepResponse> parseSteps(
        String workflowDefinitionJson,
        String defaultSurveyRefName,
        boolean inviteRequired,
        boolean registrationRequired,
        boolean surveyRequired,
        boolean adminApprovalRequired
    ) {
        String effectiveJson = workflowDefinitionJson;
        if (effectiveJson == null || effectiveJson.isBlank()) {
            effectiveJson = buildDefinitionJson(defaultSurveyRefName, inviteRequired, registrationRequired, surveyRequired, adminApprovalRequired);
        }

        try {
            Map<String, Object> root = objectMapper.readValue(effectiveJson, new TypeReference<>() {});
            Object rawSteps = root.get("steps");
            if (!(rawSteps instanceof List<?> rawList) || rawList.isEmpty()) {
                return fallbackSteps(defaultSurveyRefName, inviteRequired, registrationRequired, surveyRequired, adminApprovalRequired);
            }

            List<TenantOnboardingWorkflowStepResponse> steps = new ArrayList<>();
            for (Object rawStep : rawList) {
                if (!(rawStep instanceof Map<?, ?> map)) {
                    continue;
                }
                String key = stringify(map.get("key"));
                String label = stringify(map.get("label"));
                String type = stringify(map.get("type"));
                String description = stringify(map.get("description"));
                boolean required = asBoolean(map.get("required"), true);
                String surveyRefName = stringify(map.get("surveyRefName"));

                steps.add(TenantOnboardingWorkflowStepResponse.builder()
                    .key(key != null ? key : normalizeStepKey(type, label))
                    .label(label != null ? label : humanize(type))
                    .type(type != null ? type : "custom")
                    .description(description)
                    .required(required)
                    .surveyRefName(surveyRefName != null ? surveyRefName : ("survey".equalsIgnoreCase(type) ? normalizeSurveyRefName(defaultSurveyRefName) : null))
                    .build());
            }

            return steps.isEmpty()
                ? fallbackSteps(defaultSurveyRefName, inviteRequired, registrationRequired, surveyRequired, adminApprovalRequired)
                : steps;
        }
        catch (Exception ignored) {
            return fallbackSteps(defaultSurveyRefName, inviteRequired, registrationRequired, surveyRequired, adminApprovalRequired);
        }
    }

    public String normalizeSurveyRefName(String surveyRefName) {
        return surveyRefName == null || surveyRefName.isBlank()
            ? TenantOnboardingWorkflow.DEFAULT_SURVEY_REF_NAME
            : surveyRefName.trim();
    }

    private List<TenantOnboardingWorkflowStepResponse> fallbackSteps(
        String defaultSurveyRefName,
        boolean inviteRequired,
        boolean registrationRequired,
        boolean surveyRequired,
        boolean adminApprovalRequired
    ) {
        List<TenantOnboardingWorkflowStepResponse> steps = new ArrayList<>();
        if (inviteRequired) {
            steps.add(stepResponse("invite", "Invite User", "access_invite", true,
                "Invite the user into the tenant and establish the initial access scope.", null));
        }
        if (registrationRequired) {
            steps.add(stepResponse("registration", "Registration", "registration_request", true,
                "Capture the user and company registration details for this tenant.", null));
        }
        if (surveyRequired) {
            steps.add(stepResponse("survey", "Onboarding Survey", "survey", true,
                "Collect the tenant's onboarding information and readiness details.", normalizeSurveyRefName(defaultSurveyRefName)));
        }
        if (adminApprovalRequired) {
            steps.add(stepResponse("approval", "Admin Approval", "admin_approval", true,
                "Review the onboarding submission and approve access for the tenant.", null));
        }
        steps.add(stepResponse("activation", "Account Activation", "account_activation", true,
            "Finalize the onboarding flow and make the user's tenant access available.", null));
        return steps;
    }

    private TenantOnboardingWorkflowStepResponse stepResponse(
        String key,
        String label,
        String type,
        boolean required,
        String description,
        String surveyRefName
    ) {
        return TenantOnboardingWorkflowStepResponse.builder()
            .key(key)
            .label(label)
            .type(type)
            .required(required)
            .description(description)
            .surveyRefName(surveyRefName)
            .build();
    }

    private Map<String, Object> step(String key, String label, String type, boolean required, String description) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", key);
        step.put("label", label);
        step.put("type", type);
        step.put("required", required);
        step.put("description", description);
        return step;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            String normalized = str.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }
        return defaultValue;
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String humanize(String type) {
        if (type == null || type.isBlank()) {
            return "Custom Step";
        }
        return type.trim().replace('_', ' ');
    }

    private String normalizeStepKey(String type, String label) {
        String source = type != null && !type.isBlank() ? type : label;
        if (source == null || source.isBlank()) {
            return "custom-step";
        }
        return source.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}

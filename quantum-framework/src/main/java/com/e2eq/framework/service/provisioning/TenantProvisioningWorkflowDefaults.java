package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.rest.responses.TenantProvisioningWorkflowStepResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class TenantProvisioningWorkflowDefaults {

    public static final String STEP_REALM_CATALOG = "realm_catalog";
    public static final String STEP_REALM_MEMBERSHIP = "realm_membership";
    public static final String STEP_REALM_MIGRATIONS = "realm_migrations";
    public static final String STEP_TENANT_IDENTITIES = "tenant_identities";
    public static final String STEP_BASE_SEED_PACKS = "base_seed_packs";
    public static final String STEP_REQUESTED_ARCHETYPES = "requested_archetypes";
    public static final String STEP_VERIFICATION = "verification";

    public static final Set<String> REQUIRED_STEP_KEYS = Set.of(
        STEP_REALM_CATALOG,
        STEP_REALM_MEMBERSHIP,
        STEP_REALM_MIGRATIONS,
        STEP_TENANT_IDENTITIES,
        STEP_BASE_SEED_PACKS,
        STEP_VERIFICATION
    );

    public static final Set<String> SUPPORTED_STEP_KEYS = Set.of(
        STEP_REALM_CATALOG,
        STEP_REALM_MEMBERSHIP,
        STEP_REALM_MIGRATIONS,
        STEP_TENANT_IDENTITIES,
        STEP_BASE_SEED_PACKS,
        STEP_REQUESTED_ARCHETYPES,
        STEP_VERIFICATION
    );

    @Inject
    ObjectMapper objectMapper;

    public String buildDefinitionJson() {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("kind", "tenant-provisioning");
        definition.put("version", 1);
        definition.put("steps", List.of(
            step(STEP_REALM_CATALOG, "Create Realm Record", true,
                "Create or verify the realm catalog entry that anchors the tenant boundary."),
            step(STEP_REALM_MEMBERSHIP, "Update Realm Membership", true,
                "Persist the system catalog membership entry used to track the tenant realm."),
            step(STEP_REALM_MIGRATIONS, "Run Realm Migrations", true,
                "Initialize the tenant database schema and apply outstanding migrations."),
            step(STEP_TENANT_IDENTITIES, "Seed Tenant Identities", true,
                "Create or reconcile the admin, baseline admin, demo identities, and profiles."),
            step(STEP_BASE_SEED_PACKS, "Apply Base Seed Packs", true,
                "Apply the default tenant seed packs that establish the baseline operating surface."),
            step(STEP_REQUESTED_ARCHETYPES, "Apply Requested Archetypes", false,
                "Apply the optional archetype-specific seed packs selected during provisioning."),
            step(STEP_VERIFICATION, "Verify Tenant Initialization", true,
                "Apply indexes and validate that the tenant realm is initialized and ready.")
        ));
        try {
            return objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not build default tenant provisioning workflow definition.", e);
        }
    }

    public List<TenantProvisioningWorkflowStepResponse> parseSteps(String workflowDefinitionJson) {
        String effectiveJson = workflowDefinitionJson;
        if (effectiveJson == null || effectiveJson.isBlank()) {
            effectiveJson = buildDefinitionJson();
        }

        try {
            Map<String, Object> root = objectMapper.readValue(effectiveJson, new TypeReference<>() {});
            Object rawSteps = root.get("steps");
            if (!(rawSteps instanceof List<?> rawList) || rawList.isEmpty()) {
                return fallbackSteps();
            }

            List<TenantProvisioningWorkflowStepResponse> steps = new ArrayList<>();
            for (Object rawStep : rawList) {
                if (!(rawStep instanceof Map<?, ?> map)) {
                    continue;
                }
                String key = stringify(map.get("key"));
                String label = stringify(map.get("label"));
                String type = stringify(map.get("type"));
                String description = stringify(map.get("description"));
                boolean required = asBoolean(map.get("required"), REQUIRED_STEP_KEYS.contains(key));

                steps.add(TenantProvisioningWorkflowStepResponse.builder()
                    .key(key)
                    .label(label != null ? label : humanize(key))
                    .type(type != null ? type : key)
                    .description(description)
                    .required(required)
                    .build());
            }

            return steps.isEmpty() ? fallbackSteps() : steps;
        } catch (Exception ignored) {
            return fallbackSteps();
        }
    }

    public List<TenantProvisioningWorkflowStepResponse> validateSteps(String workflowDefinitionJson) {
        List<TenantProvisioningWorkflowStepResponse> steps = parseSteps(workflowDefinitionJson);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Tenant provisioning workflow must define at least one step.");
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        for (TenantProvisioningWorkflowStepResponse step : steps) {
            String key = step.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Each tenant provisioning workflow step requires a key.");
            }
            if (!SUPPORTED_STEP_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unsupported tenant provisioning workflow step: " + key);
            }
            if (!seenKeys.add(key)) {
                throw new IllegalArgumentException("Tenant provisioning workflow contains a duplicate step key: " + key);
            }
        }

        for (String requiredKey : REQUIRED_STEP_KEYS) {
            if (!seenKeys.contains(requiredKey)) {
                throw new IllegalArgumentException("Tenant provisioning workflow must include required step: " + requiredKey);
            }
        }
        return steps;
    }

    private List<TenantProvisioningWorkflowStepResponse> fallbackSteps() {
        return List.of(
            stepResponse(STEP_REALM_CATALOG, "Create Realm Record", true,
                "Create or verify the realm catalog entry that anchors the tenant boundary."),
            stepResponse(STEP_REALM_MEMBERSHIP, "Update Realm Membership", true,
                "Persist the system catalog membership entry used to track the tenant realm."),
            stepResponse(STEP_REALM_MIGRATIONS, "Run Realm Migrations", true,
                "Initialize the tenant database schema and apply outstanding migrations."),
            stepResponse(STEP_TENANT_IDENTITIES, "Seed Tenant Identities", true,
                "Create or reconcile the admin, baseline admin, demo identities, and profiles."),
            stepResponse(STEP_BASE_SEED_PACKS, "Apply Base Seed Packs", true,
                "Apply the default tenant seed packs that establish the baseline operating surface."),
            stepResponse(STEP_REQUESTED_ARCHETYPES, "Apply Requested Archetypes", false,
                "Apply the optional archetype-specific seed packs selected during provisioning."),
            stepResponse(STEP_VERIFICATION, "Verify Tenant Initialization", true,
                "Apply indexes and validate that the tenant realm is initialized and ready.")
        );
    }

    private Map<String, Object> step(String key, String label, boolean required, String description) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", key);
        step.put("label", label);
        step.put("type", key);
        step.put("required", required);
        step.put("description", description);
        return step;
    }

    private TenantProvisioningWorkflowStepResponse stepResponse(String key, String label, boolean required, String description) {
        return TenantProvisioningWorkflowStepResponse.builder()
            .key(key)
            .label(label)
            .type(key)
            .required(required)
            .description(description)
            .build();
    }

    private String stringify(Object input) {
        return input != null ? String.valueOf(input) : null;
    }

    private boolean asBoolean(Object input, boolean defaultValue) {
        if (input instanceof Boolean value) {
            return value;
        }
        if (input instanceof String value) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }

    private String humanize(String key) {
        if (key == null || key.isBlank()) {
            return "Step";
        }
        String normalized = key.replace('_', ' ').trim();
        StringBuilder builder = new StringBuilder(normalized.length());
        boolean capitalizeNext = true;
        for (char ch : normalized.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                capitalizeNext = true;
                builder.append(ch);
                continue;
            }
            builder.append(capitalizeNext ? Character.toUpperCase(ch) : ch);
            capitalizeNext = false;
        }
        return builder.toString();
    }
}

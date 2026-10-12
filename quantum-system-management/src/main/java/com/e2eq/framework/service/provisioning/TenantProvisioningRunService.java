package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.model.persistent.morphia.TenantProvisioningRunRepo;
import com.e2eq.framework.api.tenant.TenantDeploymentTopology;
import com.e2eq.framework.model.security.TenantProvisioningRun;
import com.e2eq.framework.model.security.TenantProvisioningWorkflow;
import com.e2eq.framework.rest.responses.TenantProvisioningRunResponse;
import com.e2eq.framework.rest.responses.TenantProvisioningRunStepResponse;
import com.e2eq.framework.rest.responses.TenantProvisioningWorkflowStepResponse;
import com.e2eq.framework.service.TenantProvisioningService;
import com.e2eq.framework.util.EnvConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class TenantProvisioningRunService {

    @Inject
    TenantProvisioningRunRepo runRepo;

    @Inject
    TenantProvisioningWorkflowService workflowService;

    @Inject
    TenantProvisioningWorkflowDefaults workflowDefaults;

    @Inject
    TenantProvisioningService provisioningService;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    TenantInitializationPlanner initializationPlanner;

    public TenantProvisioningRunResponse startProvisioning(TenantProvisioningService.ProvisionTenantCommand command) {
        return startProvisioning(command, newExecutionRef());
    }

    /** Repeated submission observes the same run; only the explicit retry endpoint may re-execute it. */
    public TenantProvisioningRunResponse startProvisioning(TenantProvisioningService.ProvisionTenantCommand command,
                                                          String executionRef) {
        String systemRealm = envConfigUtils.getSystemRealm();
        if (executionRef == null || !executionRef.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("PROVISIONING_REQUEST_ID_INVALID");
        }
        String fingerprint = requestFingerprint(command);
        var existing = runRepo.findByExecutionRef(systemRealm, executionRef);
        if (existing.isPresent()) {
            var run = existing.get();
            requireMatchingRequest(run, fingerprint);
            return toResponse(run);
        }
        TenantProvisioningService.ProvisioningContext context = provisioningService.initializeContext(command);
        context.setInitializationPlan(initializationPlanner.plan(context));
        String planJson = initializationPlanner.encode(context.getInitializationPlan());
        TenantProvisioningWorkflow workflow = workflowService.resolveCurrentWorkflow(systemRealm);
        List<TenantProvisioningWorkflowStepResponse> workflowSteps = workflowDefaults.validateSteps(workflow.getWorkflowDefinitionJson());
        if (!context.getArchetypes().isEmpty() && workflowSteps.stream().noneMatch(step ->
                TenantProvisioningWorkflowDefaults.STEP_REQUESTED_ARCHETYPES.equals(step.getKey())))
            throw new IllegalArgumentException("PROVISIONING_WORKFLOW_ARCHETYPE_STEP_REQUIRED");

        TenantProvisioningRun run = TenantProvisioningRun.builder()
            .refName(executionRef)
            .executionRef(executionRef)
            .requestFingerprint(fingerprint)
            .initializationPlanJson(planJson)
            .initializationPlanSha256(TenantInitializationPlanner.digest(planJson))
            .displayName("Provision tenant " + context.getRealmId())
            .workflowRefName(workflow.getRefName())
            .workflowVersion(workflow.getWorkflowVersion())
            .workflowDefinitionJson(workflow.getWorkflowDefinitionJson())
            .realmId(context.getRealmId())
            .tenantId(context.getTenantId())
            .deploymentTopology(context.getDeploymentTopology().name())
            .tenantDisplayName(context.getNormalizedTenantDisplayName())
            .tenantEmailDomain(command.getTenantEmailDomain())
            .orgRefName(command.getOrgRefName())
            .accountId(command.getAccountId())
            .adminUserId(command.getAdminUserId())
            .adminDisplayName(command.getAdminDisplayName())
            .adminSubject(command.getAdminSubject())
            .adminCredentialType(command.getAdminCredentialType())
            .applicationId(context.getApplicationId())
            .overwriteAll(command.isOverwriteAll())
            .requestedArchetypes(new ArrayList<>(context.getArchetypes()))
            .steps(toStepStates(workflowSteps))
            .build();
        try {
            run = runRepo.save(systemRealm, run);
        } catch (com.mongodb.MongoWriteException conflict) {
            if (conflict.getError().getCategory() != com.mongodb.ErrorCategory.DUPLICATE_KEY) throw conflict;
            // The unique executionRef index arbitrates concurrent initial requests.
            // The loser observes the winner; it must never execute or retry it here.
            var winner = runRepo.findByExecutionRef(systemRealm, executionRef).orElseThrow(() -> conflict);
            requireMatchingRequest(winner, fingerprint);
            return toResponse(winner);
        }
        return toResponse(execute(systemRealm, run, context));
    }

    static void requireMatchingRequest(TenantProvisioningRun run, String fingerprint) {
        if (run.getRequestFingerprint() == null) {
            throw new IllegalStateException("PROVISIONING_REQUEST_UNPINNED: legacy runs require reviewed recovery before execution");
        }
        if (!run.getRequestFingerprint().equals(fingerprint)) {
            throw new IllegalStateException("PROVISIONING_REQUEST_CONFLICT: operation key belongs to a different command");
        }
    }

    /** Passwords are deliberately excluded: explicit retries may supply them again. */
    static String requestFingerprint(TenantProvisioningService.ProvisionTenantCommand command) {
        java.util.Objects.requireNonNull(command, "command");
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("version", 1);
        fields.put("tenantDisplayName", command.getTenantDisplayName());
        fields.put("tenantEmailDomain", command.getTenantEmailDomain());
        fields.put("orgRefName", command.getOrgRefName());
        fields.put("accountId", command.getAccountId());
        fields.put("adminUserId", command.getAdminUserId());
        fields.put("adminDisplayName", command.getAdminDisplayName());
        fields.put("adminSubject", command.getAdminSubject());
        fields.put("adminCredentialType", command.getAdminCredentialType());
        fields.put("applicationId", command.getApplicationId());
        fields.put("deploymentTopology", command.getDeploymentTopology());
        fields.put("placementRealmId", command.getPlacementRealmId());
        fields.put("archetypes", command.getArchetypes());
        fields.put("overwriteAll", command.isOverwriteAll());
        try {
            byte[] payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(fields);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("PROVISIONING_REQUEST_FINGERPRINT_FAILED", e);
        }
    }

    public TenantProvisioningRunResponse getRun(String executionRef) {
        return toResponse(loadRun(executionRef));
    }

    public TenantProvisioningRunResponse retry(String executionRef, String adminPassword) {
        String systemRealm = envConfigUtils.getSystemRealm();
        TenantProvisioningRun run = loadRun(executionRef);
        if (run.getStatus() != TenantProvisioningRun.Status.FAILED) {
            return toResponse(run);
        }
        if (run.isRetryRequiresAdminPassword() && (adminPassword == null || adminPassword.isBlank())) {
            throw new IllegalArgumentException("Retrying this provisioning run requires the admin password because the failure occurred before tenant identities were fully reconciled.");
        }

        TenantProvisioningService.ProvisionTenantCommand command = TenantProvisioningService.ProvisionTenantCommand.builder()
            .tenantDisplayName(run.getTenantDisplayName())
            .tenantEmailDomain(run.getTenantEmailDomain())
            .orgRefName(run.getOrgRefName())
            .accountId(run.getAccountId())
            .adminUserId(run.getAdminUserId())
            .adminDisplayName(run.getAdminDisplayName())
            .adminSubject(run.getAdminSubject())
            .adminCredentialType(run.getAdminCredentialType())
            .applicationId(run.getApplicationId())
            .adminPassword(adminPassword)
            .deploymentTopology(parseDeploymentTopology(run.getDeploymentTopology()))
            .placementRealmId(run.getRealmId())
            .archetypes(run.getRequestedArchetypes())
            .overwriteAll(run.isOverwriteAll())
            .build();

        TenantProvisioningService.ProvisioningContext context = provisioningService.initializeContext(command);
        context.setInitializationPlan(initializationPlanner.decode(run.getInitializationPlanJson(), run.getInitializationPlanSha256()));
        initializationPlanner.validate(context.getInitializationPlan(), context);
        restoreResult(context, run);
        if (!runRepo.claimFailedRun(systemRealm, executionRef)) {
            return getRun(executionRef);
        }
        resetForRetry(run);
        run = runRepo.save(systemRealm, run);
        return toResponse(execute(systemRealm, run, context));
    }

    private TenantProvisioningRun execute(String systemRealm, TenantProvisioningRun run, TenantProvisioningService.ProvisioningContext context) {
        try {
            initializationPlanner.validate(context.getInitializationPlan(), context);
        } catch (RuntimeException failure) {
            run.setStatus(TenantProvisioningRun.Status.FAILED);
            run.setFailureReason("Initialization plan validation failed before provisioning writes.");
            run.setFailureDetail(rootMessage(failure));
            run.setUpdatedAt(Instant.now());
            runRepo.save(systemRealm, run);
            throw new TenantProvisioningRunFailedException(run, resolveStatus(failure), run.getFailureReason(), failure);
        }
        Instant now = Instant.now();
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        run.setStatus(TenantProvisioningRun.Status.RUNNING);
        run.setUpdatedAt(now);
        run.setCompletedAt(null);
        run.setFailureReason(null);
        run.setFailureDetail(null);
        run.setRetryRequiresAdminPassword(false);
        runRepo.save(systemRealm, run);

        for (TenantProvisioningRun.StepState step : run.getSteps()) {
            if (step.getStatus() == TenantProvisioningRun.StepStatus.COMPLETED
                || step.getStatus() == TenantProvisioningRun.StepStatus.SKIPPED) {
                continue;
            }

            Instant stepNow = Instant.now();
            step.setStatus(TenantProvisioningRun.StepStatus.RUNNING);
            step.setAttemptCount(step.getAttemptCount() + 1);
            if (step.getStartedAt() == null) {
                step.setStartedAt(stepNow);
            }
            step.setUpdatedAt(stepNow);
            step.setCompletedAt(null);
            step.setFailureReason(null);
            run.setCurrentStepKey(step.getKey());
            run.setCurrentStepLabel(step.getLabel());
            run.setUpdatedAt(stepNow);
            runRepo.save(systemRealm, run);

            try {
                if (shouldSkip(step, context)) {
                    step.setStatus(TenantProvisioningRun.StepStatus.SKIPPED);
                } else {
                    executeStep(step.getKey(), context);
                    step.setStatus(TenantProvisioningRun.StepStatus.COMPLETED);
                }
                step.setCompletedAt(Instant.now());
                step.setUpdatedAt(step.getCompletedAt());
                copyResult(run, context.getResult());
                run.setUpdatedAt(step.getCompletedAt());
                runRepo.save(systemRealm, run);
            } catch (Exception e) {
                String message = rootMessage(e);
                step.setStatus(TenantProvisioningRun.StepStatus.FAILED);
                step.setFailureReason(message);
                step.setUpdatedAt(Instant.now());
                run.setStatus(TenantProvisioningRun.Status.FAILED);
                run.setFailureReason("Tenant provisioning failed at step '" + step.getLabel() + "'.");
                run.setFailureDetail(message);
                run.setRetryRequiresAdminPassword(context.getCommand().getAdminCredentialType()
                    != com.e2eq.framework.model.security.CredentialType.EMAIL && requiresPasswordForRetry(step.getKey()));
                run.setUpdatedAt(step.getUpdatedAt());
                copyResult(run, context.getResult());
                runRepo.save(systemRealm, run);
                throw new TenantProvisioningRunFailedException(
                    run,
                    resolveStatus(e),
                    run.getFailureReason(),
                    e
                );
            }
        }

        Instant completedAt = Instant.now();
        copyResult(run, context.getResult());
        run.setStatus(TenantProvisioningRun.Status.COMPLETED);
        run.setCurrentStepKey(null);
        run.setCurrentStepLabel(null);
        run.setFailureReason(null);
        run.setFailureDetail(null);
        run.setRetryRequiresAdminPassword(false);
        run.setUpdatedAt(completedAt);
        run.setCompletedAt(completedAt);
        runRepo.save(systemRealm, run);
        return run;
    }

    private void executeStep(String stepKey, TenantProvisioningService.ProvisioningContext context) {
        switch (stepKey) {
            case TenantProvisioningWorkflowDefaults.STEP_REALM_CATALOG ->
                provisioningService.ensureRealmCatalog(context);
            case TenantProvisioningWorkflowDefaults.STEP_REALM_MEMBERSHIP ->
                provisioningService.ensureRealmMembership(context);
            case TenantProvisioningWorkflowDefaults.STEP_REALM_MIGRATIONS ->
                provisioningService.runRealmMigrations(context);
            case TenantProvisioningWorkflowDefaults.STEP_TENANT_IDENTITIES ->
                provisioningService.ensureTenantIdentities(context);
            case TenantProvisioningWorkflowDefaults.STEP_BASE_SEED_PACKS ->
                provisioningService.applyBaseSeedPacks(context);
            case TenantProvisioningWorkflowDefaults.STEP_REQUESTED_ARCHETYPES ->
                provisioningService.applyRequestedArchetypes(context);
            case TenantProvisioningWorkflowDefaults.STEP_VERIFICATION ->
                provisioningService.verifyRealmInitialization(context);
            default -> throw new IllegalArgumentException("Unsupported tenant provisioning step: " + stepKey);
        }
    }

    private boolean shouldSkip(TenantProvisioningRun.StepState step, TenantProvisioningService.ProvisioningContext context) {
        if (TenantProvisioningWorkflowDefaults.STEP_REALM_MIGRATIONS.equals(step.getKey())
            && context.getDeploymentTopology() == TenantDeploymentTopology.POOLED_REALM) {
            return true;
        }
        return TenantProvisioningWorkflowDefaults.STEP_REQUESTED_ARCHETYPES.equals(step.getKey())
            && (context.getArchetypes() == null || context.getArchetypes().isEmpty());
    }

    private boolean requiresPasswordForRetry(String stepKey) {
        return TenantProvisioningWorkflowDefaults.STEP_TENANT_IDENTITIES.equals(stepKey);
    }

    private Response.Status resolveStatus(Exception exception) {
        if (exception instanceof IllegalArgumentException) {
            return Response.Status.BAD_REQUEST;
        }
        if (exception instanceof IllegalStateException) {
            return Response.Status.CONFLICT;
        }
        return Response.Status.INTERNAL_SERVER_ERROR;
    }

    private void restoreResult(TenantProvisioningService.ProvisioningContext context, TenantProvisioningRun run) {
        context.getResult().realmCreated = run.isRealmCreated();
        context.getResult().userCreated = run.isUserCreated();
        context.getResult().appliedSeedArchetypes.addAll(run.getAppliedSeedArchetypes());
        context.getResult().warnings.addAll(run.getWarnings());
    }

    private void copyResult(TenantProvisioningRun run, TenantProvisioningService.ProvisionResult result) {
        run.setRealmCreated(result.realmCreated);
        run.setUserCreated(result.userCreated);
        run.setAppliedSeedArchetypes(new ArrayList<>(result.appliedSeedArchetypes));
        run.setWarnings(new ArrayList<>(result.warnings));
    }

    private TenantProvisioningRun loadRun(String executionRef) {
        return runRepo.findByExecutionRef(envConfigUtils.getSystemRealm(), executionRef)
            .orElseThrow(() -> new NotFoundException("Tenant provisioning run was not found: " + executionRef));
    }

    private void resetForRetry(TenantProvisioningRun run) {
        run.setRetryCount(run.getRetryCount() + 1);
        run.setStatus(TenantProvisioningRun.Status.PENDING);
        run.setCurrentStepKey(null);
        run.setCurrentStepLabel(null);
        run.setFailureReason(null);
        run.setFailureDetail(null);
        run.setCompletedAt(null);
        run.setRetryRequiresAdminPassword(false);

        boolean reset = false;
        for (TenantProvisioningRun.StepState step : run.getSteps()) {
            if (step.getStatus() == TenantProvisioningRun.StepStatus.FAILED) {
                reset = true;
            }
            if (reset || step.getStatus() == TenantProvisioningRun.StepStatus.RUNNING) {
                step.setStatus(TenantProvisioningRun.StepStatus.PENDING);
                step.setCompletedAt(null);
                step.setFailureReason(null);
            }
        }
    }

    private List<TenantProvisioningRun.StepState> toStepStates(List<TenantProvisioningWorkflowStepResponse> steps) {
        List<TenantProvisioningRun.StepState> result = new ArrayList<>();
        for (TenantProvisioningWorkflowStepResponse step : steps) {
            result.add(TenantProvisioningRun.StepState.builder()
                .key(step.getKey())
                .label(step.getLabel())
                .type(step.getType())
                .description(step.getDescription())
                .required(step.isRequired())
                .status(TenantProvisioningRun.StepStatus.PENDING)
                .build());
        }
        return result;
    }

    private TenantProvisioningRunResponse toResponse(TenantProvisioningRun run) {
        List<TenantProvisioningRunStepResponse> steps = new ArrayList<>();
        for (TenantProvisioningRun.StepState step : run.getSteps()) {
            steps.add(TenantProvisioningRunStepResponse.builder()
                .key(step.getKey())
                .label(step.getLabel())
                .type(step.getType())
                .description(step.getDescription())
                .required(step.isRequired())
                .status(step.getStatus() != null ? step.getStatus().name() : null)
                .attemptCount(step.getAttemptCount())
                .startedAt(step.getStartedAt())
                .updatedAt(step.getUpdatedAt())
                .completedAt(step.getCompletedAt())
                .failureReason(step.getFailureReason())
                .build());
        }

        return TenantProvisioningRunResponse.builder()
            .id(run.getId() != null ? run.getId().toHexString() : null)
            .executionRef(run.getExecutionRef())
            .workflowRefName(run.getWorkflowRefName())
            .workflowVersion(run.getWorkflowVersion())
            .runtimeExecutionRef(run.getRuntimeExecutionRef())
            .runtimeStatus(run.getRuntimeStatus())
            .realmId(run.getRealmId())
            .tenantId(run.getTenantId())
            .deploymentTopology(run.getDeploymentTopology())
            .tenantDisplayName(run.getTenantDisplayName())
            .tenantEmailDomain(run.getTenantEmailDomain())
            .orgRefName(run.getOrgRefName())
            .accountId(run.getAccountId())
            .adminUserId(run.getAdminUserId())
            .adminSubject(run.getAdminSubject())
            .status(run.getStatus() != null ? run.getStatus().name() : null)
            .currentStepKey(run.getCurrentStepKey())
            .currentStepLabel(run.getCurrentStepLabel())
            .failureReason(run.getFailureReason())
            .failureDetail(run.getFailureDetail())
            .retryRequiresAdminPassword(run.isRetryRequiresAdminPassword())
            .retryCount(run.getRetryCount())
            .realmCreated(run.isRealmCreated())
            .userCreated(run.isUserCreated())
            .startedAt(run.getStartedAt())
            .updatedAt(run.getUpdatedAt())
            .completedAt(run.getCompletedAt())
            .requestedArchetypes(run.getRequestedArchetypes() == null ? List.of() : new ArrayList<>(run.getRequestedArchetypes()))
            .appliedSeedArchetypes(run.getAppliedSeedArchetypes() == null ? List.of() : new ArrayList<>(run.getAppliedSeedArchetypes()))
            .warnings(run.getWarnings() == null ? List.of() : new ArrayList<>(run.getWarnings()))
            .steps(steps)
            .build();
    }

    private TenantDeploymentTopology parseDeploymentTopology(String value) {
        if (value == null || value.isBlank()) {
            return TenantDeploymentTopology.DEDICATED_REALM;
        }
        try {
            return TenantDeploymentTopology.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Unsupported deployment topology persisted on provisioning run: " + value,
                exception
            );
        }
    }

    private String newExecutionRef() {
        return "tenant-provisioning-" + UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = throwable.getMessage();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message != null ? message : "Provisioning failed.";
    }
}

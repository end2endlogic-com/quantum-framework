package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class TenantProvisioningRunResponse {
    private String id;
    private String executionRef;
    private String workflowRefName;
    private int workflowVersion;
    private String runtimeExecutionRef;
    private String runtimeStatus;
    private String realmId;
    private String tenantDisplayName;
    private String tenantEmailDomain;
    private String orgRefName;
    private String accountId;
    private String adminUserId;
    private String adminSubject;
    private String status;
    private String currentStepKey;
    private String currentStepLabel;
    private String failureReason;
    private String failureDetail;
    private boolean retryRequiresAdminPassword;
    private int retryCount;
    private boolean realmCreated;
    private boolean userCreated;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant completedAt;
    @Builder.Default
    private List<String> requestedArchetypes = new ArrayList<>();
    @Builder.Default
    private List<String> appliedSeedArchetypes = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    @Builder.Default
    private List<TenantProvisioningRunStepResponse> steps = new ArrayList<>();
}

package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class TenantProvisioningRunStepResponse {
    private String key;
    private String label;
    private String type;
    private String description;
    private boolean required;
    private String status;
    private int attemptCount;
    private Instant startedAt;
    private Instant updatedAt;
    private Instant completedAt;
    private String failureReason;
}

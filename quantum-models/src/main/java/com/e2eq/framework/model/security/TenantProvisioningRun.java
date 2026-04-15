package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity(value = "tenant_provisioning_runs", useDiscriminator = false)
@Indexes({
    @Index(fields = {
        @Field("executionRef")
    }, options = @IndexOptions(unique = true, name = "uidx_tenant_provisioning_execution_ref")),
    @Index(fields = {
        @Field("realmId")
    }, options = @IndexOptions(name = "idx_tenant_provisioning_realm")),
    @Index(fields = {
        @Field("status")
    }, options = @IndexOptions(name = "idx_tenant_provisioning_status"))
})
@Data
@SuperBuilder
@NoArgsConstructor
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
public class TenantProvisioningRun extends BaseModel {

    private String executionRef;
    private String workflowRefName;
    private int workflowVersion;
    private String workflowDefinitionJson;
    private String runtimeExecutionRef;
    private String runtimeStatus;

    private String realmId;
    private String tenantDisplayName;
    private String tenantEmailDomain;
    private String orgRefName;
    private String accountId;
    private String adminUserId;
    private String adminSubject;

    @Builder.Default
    private boolean overwriteAll = true;

    @Builder.Default
    private Status status = Status.PENDING;

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
    private List<StepState> steps = new ArrayList<>();

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TENANT_PROVISIONING";
    }

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepState {
        private String key;
        private String label;
        private String type;
        private String description;
        @Builder.Default
        private boolean required = true;
        @Builder.Default
        private StepStatus status = StepStatus.PENDING;
        private int attemptCount;
        private Instant startedAt;
        private Instant updatedAt;
        private Instant completedAt;
        private String failureReason;
    }
}

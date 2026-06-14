package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity(value = "tenant_provisioning_workflows", useDiscriminator = false)
@Indexes({
    @Index(fields = {
        @Field("refName")
    }, options = @IndexOptions(unique = true, name = "uidx_tenant_provisioning_workflow_ref_name"))
})
@Data
@SuperBuilder
@NoArgsConstructor
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
public class TenantProvisioningWorkflow extends BaseModel {

    public static final String DEFAULT_REF_NAME = "default-tenant-provisioning";

    @Builder.Default
    private boolean workflowEnabled = true;

    private String description;

    @Builder.Default
    private int workflowVersion = 1;

    private String workflowDefinitionJson;

    private String completionMessage;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TENANT_PROVISIONING";
    }
}

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

@Entity(value = "tenant_onboarding_workflows", useDiscriminator = false)
@Indexes({
    @Index(fields = {
        @Field("refName")
    }, options = @IndexOptions(unique = true, name = "uidx_tenant_onboarding_workflow_ref_name"))
})
@Data
@SuperBuilder
@NoArgsConstructor
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
public class TenantOnboardingWorkflow extends BaseModel {

    public static final String DEFAULT_REF_NAME = "default-tenant-onboarding";
    public static final String DEFAULT_SURVEY_REF_NAME = "tenant-user-onboarding";

    @Builder.Default
    private boolean workflowEnabled = true;

    private String description;

    @Builder.Default
    private boolean inviteRequired = true;

    @Builder.Default
    private boolean registrationRequired = true;

    @Builder.Default
    private boolean surveyRequired = true;

    @Builder.Default
    private boolean adminApprovalRequired = true;

    @Builder.Default
    private boolean autoAssignSurveyOnInvite = true;

    @Builder.Default
    private String defaultSurveyRefName = DEFAULT_SURVEY_REF_NAME;

    private String workflowDefinitionJson;

    private String completionMessage;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "TENANT_ONBOARDING";
    }
}

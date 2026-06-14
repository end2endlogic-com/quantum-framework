package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class TenantOnboardingRunResponse {
    private String realmId;
    private String groupId;
    private String runRef;
    private String workflowRefName;
    private String workflowDisplayName;
    private String status;
    private String completionMessage;
    private Date createdDate;
    private Date completedDate;
    @Builder.Default
    private List<TenantOnboardingRunTaskResponse> tasks = new ArrayList<>();
}

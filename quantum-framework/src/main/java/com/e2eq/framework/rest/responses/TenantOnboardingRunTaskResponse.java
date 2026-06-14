package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class TenantOnboardingRunTaskResponse {
    private String id;
    private String refName;
    private String displayName;
    private String status;
    private String details;
    private String result;
    private Date createdDate;
    private Date completedDate;
}

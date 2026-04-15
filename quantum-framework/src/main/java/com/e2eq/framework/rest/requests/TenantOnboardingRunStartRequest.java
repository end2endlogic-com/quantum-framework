package com.e2eq.framework.rest.requests;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class TenantOnboardingRunStartRequest {
    private String runRef;
    private String displayName;
    private String description;
    private String actorRef;
    private String userId;
    private String subjectId;
    private String email;
    private JsonNode metadata;
}

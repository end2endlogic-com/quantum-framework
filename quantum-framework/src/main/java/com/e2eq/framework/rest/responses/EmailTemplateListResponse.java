package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailTemplateListResponse {
    private String refName;
    private String displayName;
    private String templateKey;
    private String description;
    private Boolean active;
    private String sourceType;
    private String functionalArea;
}

package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailTemplateDetailResponse {
    private String refName;
    private String displayName;
    private String templateKey;
    private String description;
    private Boolean active;
    private String sourceType;
    private String classpathTemplateBaseName;
    private String functionalArea;
    private String subjectTemplate;
    private String htmlTemplate;
    private String textTemplate;
    private String sampleContextJson;
    private String schemaVersion;
}

package com.e2eq.framework.rest.requests;

import lombok.Data;

@Data
public class EmailTemplateUpsertRequest {
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

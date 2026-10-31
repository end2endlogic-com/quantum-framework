package com.e2eq.framework.rest.requests;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class EmailTemplatePreviewRequest {
    private String templateKey;
    private String subjectTemplate;
    private String htmlTemplate;
    private String textTemplate;
    private JsonNode context;
}

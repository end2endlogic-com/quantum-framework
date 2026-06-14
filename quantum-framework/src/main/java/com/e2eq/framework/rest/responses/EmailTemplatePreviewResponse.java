package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailTemplatePreviewResponse {
    private String templateKey;
    private String origin;
    private String subject;
    private String htmlBody;
    private String textBody;
}

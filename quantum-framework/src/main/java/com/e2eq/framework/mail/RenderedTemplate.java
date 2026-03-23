package com.e2eq.framework.mail;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record RenderedTemplate(
    String templateKey,
    EmailTemplateDefinition.Origin origin,
    String subject,
    String htmlBody,
    String textBody
) {
}

package com.e2eq.framework.mail;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record EmailTemplateDefinition(
    String templateKey,
    String subjectTemplate,
    String htmlTemplate,
    String textTemplate,
    Origin origin
) {
    public enum Origin {
        CLASSPATH,
        REALM_DB
    }
}

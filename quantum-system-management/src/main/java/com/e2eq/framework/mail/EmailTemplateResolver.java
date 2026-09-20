package com.e2eq.framework.mail;

import java.util.Optional;

public interface EmailTemplateResolver {
    Optional<EmailTemplateDefinition> resolve(String realm, String templateKey);
}

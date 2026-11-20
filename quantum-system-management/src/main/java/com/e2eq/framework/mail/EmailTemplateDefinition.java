package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * A resolved template for one key, channel and locale.
 *
 * @param locale             BCP 47 tag of the variant that matched; {@code null} for the default locale
 * @param providerTemplateId set when the provider holds the pre-approved template; then
 *                           nothing is rendered locally
 * @param senderSignature    provider-registered sender signature, where the provider needs one
 * @param parameterNames     parameters a send must supply
 */
@RegisterForReflection
public record EmailTemplateDefinition(
    String templateKey,
    String subjectTemplate,
    String htmlTemplate,
    String textTemplate,
    Origin origin,
    MessageChannel channel,
    String locale,
    String providerTemplateId,
    String senderSignature,
    List<String> parameterNames
) {
    public enum Origin {
        CLASSPATH,
        REALM_DB
    }

    public EmailTemplateDefinition {
        if (channel == null) channel = MessageChannel.EMAIL;
        parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);
    }

    /** Email template in the default locale: the constructor every caller used before channels. */
    public EmailTemplateDefinition(
        String templateKey,
        String subjectTemplate,
        String htmlTemplate,
        String textTemplate,
        Origin origin
    ) {
        this(templateKey, subjectTemplate, htmlTemplate, textTemplate, origin,
            MessageChannel.EMAIL, null, null, null, List.of());
    }

    /** Declared parameter names the given values do not supply (absent or {@code null}), in declared order. */
    public List<String> missingParameters(Map<String, ?> parameters) {
        return parameterNames.stream()
            .filter(name -> parameters == null || parameters.get(name) == null)
            .toList();
    }

    /** True when the provider renders it from {@link #providerTemplateId()}. */
    public boolean providerRendered() {
        return providerTemplateId != null && !providerTemplateId.isBlank();
    }
}

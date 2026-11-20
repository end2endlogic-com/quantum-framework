package com.e2eq.framework.mail;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;
import java.util.Objects;

/**
 * A channel-neutral request to send one templated message.
 *
 * @param realm          realm whose template overrides apply; {@code null} uses classpath templates only
 * @param recipient      channel and address
 * @param templateKey    one key per purpose, for example {@code auth.magic-link}
 * @param parameters     values for the template; every name the template declares is required
 * @param locale         BCP 47 language tag; {@code null} for the default locale
 * @param sender         channel-specific sender identity (an email from-address); {@code null}
 *                       uses the provider's configured default
 * @param idempotencyKey caller's key for this send, returned on the receipt and passed to
 *                       providers that support one; {@code null} when the caller has none
 */
@RegisterForReflection
public record MessageRequest(
    String realm,
    MessageRecipient recipient,
    String templateKey,
    Map<String, Object> parameters,
    String locale,
    String sender,
    String idempotencyKey
) {
    public MessageRequest {
        Objects.requireNonNull(recipient, "recipient");
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("templateKey is required");
        }
        templateKey = templateKey.trim();
        // Null values are kept so a missing declared parameter is refused as TEMPLATE_PARAMETER_MISSING.
        parameters = parameters == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
    }

    /** Parameters can carry secrets (sign-in links, codes); never log them. */
    @Override public String toString() {
        return "MessageRequest[templateKey=" + templateKey + ", recipient=" + recipient + "]";
    }
}

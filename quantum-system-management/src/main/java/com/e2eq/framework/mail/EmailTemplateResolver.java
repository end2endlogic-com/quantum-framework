package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;

import java.util.Optional;

public interface EmailTemplateResolver {
    /** Email template in the default locale; unchanged since before channels. */
    Optional<EmailTemplateDefinition> resolve(String realm, String templateKey);

    /**
     * Template for a channel and locale, falling back to the channel's default locale.
     * The default supports only email in the default locale, so a resolver written before
     * channels never answers for another channel.
     *
     * @param locale BCP 47 language tag; {@code null} for the default locale
     */
    default Optional<EmailTemplateDefinition> resolve(String realm, String templateKey, MessageChannel channel, String locale) {
        if (channel != MessageChannel.EMAIL) {
            return Optional.empty();
        }
        return resolve(realm, templateKey);
    }
}

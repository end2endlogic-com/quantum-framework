package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

/**
 * Where a message goes: a channel and an address on it (an email address, or a normalised
 * E.164 number for SMS and WhatsApp). Normalising numbers is the caller's job.
 */
@RegisterForReflection
public record MessageRecipient(MessageChannel channel, String address) {
    public MessageRecipient {
        Objects.requireNonNull(channel, "channel");
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("recipient address is required");
        }
        address = address.trim();
    }

    /** Never log addresses or phone numbers. */
    @Override public String toString() {
        return "MessageRecipient[channel=" + channel + "]";
    }
}

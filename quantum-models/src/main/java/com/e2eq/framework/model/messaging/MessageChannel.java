package com.e2eq.framework.model.messaging;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Channel a message is delivered on. Channel-neutral: vendor adapters (SMS or WhatsApp
 * providers) live outside the open-source core and register for a channel.
 */
@RegisterForReflection
public enum MessageChannel {
    EMAIL,
    SMS,
    WHATSAPP;

    /** Lower-case name used in classpath template paths, for example {@code templates/sms/...}. */
    public String pathSegment() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

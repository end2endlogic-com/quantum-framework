package com.e2eq.framework.mail;

import java.util.Objects;

/** A send that was refused or could not be handed to a provider, with the reason. */
public class MessageDeliveryException extends RuntimeException {

    public enum Reason {
        /** No adapter is registered for the recipient's channel. */
        NO_ADAPTER,
        /** More than one adapter is registered for the channel and nothing chose between them. */
        AMBIGUOUS_ADAPTER,
        /** The adapter does not handle the request's channel. */
        CHANNEL_NOT_SUPPORTED,
        /** No template for the key and channel, in the requested or the default locale. */
        TEMPLATE_NOT_FOUND,
        /** The template declares a parameter the request does not supply. */
        TEMPLATE_PARAMETER_MISSING,
        /** The template is pre-approved by a provider, which this adapter cannot send. */
        PROVIDER_TEMPLATE_UNSUPPORTED,
        /** The provider is not configured (for example no API key or sender) or refused the message. */
        DELIVERY_UNAVAILABLE
    }

    private final Reason reason;

    public MessageDeliveryException(Reason reason, String message) {
        this(reason, message, null);
    }

    public MessageDeliveryException(Reason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}

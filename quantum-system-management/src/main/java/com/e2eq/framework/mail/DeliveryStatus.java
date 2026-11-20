package com.e2eq.framework.mail;

/**
 * Delivery state of one message. A send returns {@link #QUEUED} or {@link #SENT}; later
 * states come from provider status callbacks, never from polling.
 */
public enum DeliveryStatus {
    /** The provider accepted the message; delivery is not yet confirmed. */
    QUEUED,
    /** The provider handed the message on to the carrier or mail server. */
    SENT,
    DELIVERED,
    FAILED
}

package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;

import java.time.Instant;

/**
 * Sends one rendered email. {@link #deliver} is the original contract and is unchanged;
 * {@link EmailMessageAdapter} uses {@link #deliverWithReceipt} so email takes part in
 * channel-neutral {@link MessageDelivery}.
 */
public interface EmailDeliveryService {
    void deliver(RenderedEmail email);

    /** Provider name on receipts. Senders override it with their own, for example {@code postmark}. */
    default String providerId() {
        return "email";
    }

    /**
     * Delivers and reports what the provider accepted. The default calls {@link #deliver}
     * and returns {@link DeliveryStatus#QUEUED} with no provider message id, so it trusts
     * that {@code deliver} throws when it does not send. A sender whose {@code deliver} can
     * return without sending (for example when unconfigured) must override this and throw
     * {@link MessageDeliveryException} instead.
     */
    default DeliveryReceipt deliverWithReceipt(RenderedEmail email) {
        deliver(email);
        return new DeliveryReceipt(providerId(), MessageChannel.EMAIL, null, DeliveryStatus.QUEUED, Instant.now(), null);
    }
}

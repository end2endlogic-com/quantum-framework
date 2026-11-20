package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;

/**
 * One provider for one channel, as a CDI bean. Vendor adapters (SMS, WhatsApp) live outside
 * the open-source core; the framework ships only {@link EmailMessageAdapter}.
 *
 * <p>Deliberately not a {@link MessageDelivery}, so registering an adapter never makes
 * injection of {@code MessageDelivery} ambiguous.
 */
public interface MessageChannelAdapter {
    MessageChannel channel();

    /** Stable provider name, for example {@code postmark}; used on receipts and in config. */
    String providerId();

    /** Same contract as {@link MessageDelivery#send}; the request's channel is this adapter's. */
    DeliveryReceipt send(MessageRequest request);
}

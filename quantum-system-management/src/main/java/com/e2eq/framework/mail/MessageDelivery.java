package com.e2eq.framework.mail;

/**
 * Channel-neutral message delivery: send one templated message, get the provider's receipt.
 *
 * <p>Fails closed: a send that cannot be delivered throws {@link MessageDeliveryException}
 * with a reason; it never returns a receipt for a message that was not handed to a provider.
 *
 * <p>The framework's default ({@link ChannelMessageDelivery}) dispatches to the one
 * {@link MessageChannelAdapter} registered for the recipient's channel. An application may
 * replace it, for example with a router that picks a provider per region.
 */
public interface MessageDelivery {
    DeliveryReceipt send(MessageRequest request);
}

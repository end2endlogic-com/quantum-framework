package com.e2eq.framework.mail;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Default {@link MessageDelivery}: sends through the single {@link MessageChannelAdapter}
 * registered for the recipient's channel. No adapter, or more than one, is refused rather
 * than guessed; routing between several providers belongs to an application's own
 * {@code MessageDelivery}, which replaces this default bean.
 */
@ApplicationScoped
@DefaultBean
public class ChannelMessageDelivery implements MessageDelivery {

    private final Instance<MessageChannelAdapter> adapters;

    @Inject
    public ChannelMessageDelivery(Instance<MessageChannelAdapter> adapters) {
        this.adapters = adapters;
    }

    @Override
    public DeliveryReceipt send(MessageRequest request) {
        var channel = request.recipient().channel();
        List<MessageChannelAdapter> matching = adapters.stream()
            .filter(adapter -> adapter.channel() == channel)
            .toList();
        if (matching.isEmpty()) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.NO_ADAPTER,
                "no message adapter is registered for channel " + channel);
        }
        if (matching.size() > 1) {
            throw new MessageDeliveryException(MessageDeliveryException.Reason.AMBIGUOUS_ADAPTER,
                "channel " + channel + " has several adapters ("
                    + matching.stream().map(MessageChannelAdapter::providerId).collect(Collectors.joining(", "))
                    + "); provide a MessageDelivery that routes between them");
        }
        return matching.get(0).send(request);
    }
}

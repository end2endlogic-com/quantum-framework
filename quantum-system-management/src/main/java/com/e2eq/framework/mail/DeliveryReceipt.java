package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Objects;

/**
 * What a provider returned for one accepted send.
 *
 * @param providerId        name of the adapter that sent it, for example {@code postmark}
 * @param providerMessageId the provider's message id, used to match status callbacks;
 *                          {@code null} when the provider returns none
 * @param status            starting status
 * @param acceptedAt        when the provider accepted it
 * @param idempotencyKey    the request's idempotency key, if any
 */
@RegisterForReflection
public record DeliveryReceipt(
    String providerId,
    MessageChannel channel,
    String providerMessageId,
    DeliveryStatus status,
    Instant acceptedAt,
    String idempotencyKey
) {
    public DeliveryReceipt {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}

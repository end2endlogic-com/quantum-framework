package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageDeliveryTest {

    /** Records what the email adapter hands to the sender; deliver() is the untouched legacy path. */
    private static final class CapturingSender implements EmailDeliveryService {
        final List<RenderedEmail> delivered = new ArrayList<>();
        @Override public void deliver(RenderedEmail email) { delivered.add(email); }
        @Override public String providerId() { return "capture"; }
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instanceOf(List<T> beans) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(beans.size() == 1);
        when(instance.isAmbiguous()).thenReturn(beans.size() > 1);
        when(instance.isUnsatisfied()).thenReturn(beans.isEmpty());
        if (beans.size() == 1) {
            when(instance.get()).thenReturn(beans.get(0));
            Instance.Handle<T> handle = mock(Instance.Handle.class);
            when(handle.get()).thenReturn(beans.get(0));
            when(instance.getHandle()).thenReturn(handle);
        }
        when(instance.stream()).thenAnswer(invocation -> beans.stream());
        return instance;
    }

    private static EmailMessageAdapter adapter(EmailTemplateResolver resolver, List<EmailDeliveryService> senders) {
        var render = new EmailTemplateRenderService(Engine.builder().addDefaults().build(), new ObjectMapper(), resolver);
        return new EmailMessageAdapter(resolver, render, instanceOf(senders));
    }

    private static MessageRequest emailRequest(String templateKey, Map<String, Object> parameters) {
        return new MessageRequest(null, new MessageRecipient(MessageChannel.EMAIL, " user@example.test "),
            templateKey, parameters, null, "noreply@example.test", "idem-1");
    }

    @Test
    void emailAdapterRendersAndReturnsTheSendersReceipt() {
        var sender = new CapturingSender();
        var adapter = adapter(new DefaultEmailTemplateResolver(null), List.of(sender));

        DeliveryReceipt receipt = adapter.send(emailRequest("framework-test", Map.of("name", "Quantum", "realm", "demo")));

        assertEquals(1, sender.delivered.size());
        RenderedEmail email = sender.delivered.get(0);
        assertEquals(List.of("user@example.test"), email.getTo());
        assertEquals("noreply@example.test", email.getFrom());
        assertEquals("Welcome Quantum", email.getSubject());
        assertEquals("capture", receipt.providerId());
        assertEquals(MessageChannel.EMAIL, receipt.channel());
        assertEquals(DeliveryStatus.QUEUED, receipt.status());
        assertNull(receipt.providerMessageId());
        assertEquals("idem-1", receipt.idempotencyKey());
        assertEquals("capture", adapter.providerId());
    }

    @Test
    void emailAdapterUsesTheRequestedLocale() {
        var sender = new CapturingSender();
        var adapter = adapter(new DefaultEmailTemplateResolver(null), List.of(sender));
        adapter.send(new MessageRequest(null, new MessageRecipient(MessageChannel.EMAIL, "u@example.test"),
            "framework-test", Map.of("name", "Ana", "realm", "r"), "pt-BR", null, null));
        assertEquals("Bem-vindo Ana", sender.delivered.get(0).getSubject());
        assertNull(sender.delivered.get(0).getFrom(), "no sender leaves the provider's configured from-address");
    }

    @Test
    void emailAdapterRefusesWhatItCannotSend() {
        var sender = new CapturingSender();
        var classpath = adapter(new DefaultEmailTemplateResolver(null), List.of(sender));

        var sms = new MessageRequest(null, new MessageRecipient(MessageChannel.SMS, "+15550100"), "framework-test", Map.of(), null, null, null);
        assertEquals(MessageDeliveryException.Reason.CHANNEL_NOT_SUPPORTED,
            assertThrows(MessageDeliveryException.class, () -> classpath.send(sms)).reason());

        assertEquals(MessageDeliveryException.Reason.TEMPLATE_NOT_FOUND,
            assertThrows(MessageDeliveryException.class, () -> classpath.send(emailRequest("no-such-template", Map.of()))).reason());

        EmailTemplateResolver declared = (realm, key) -> Optional.of(new EmailTemplateDefinition(key, "Sign in", null,
            "{link}", EmailTemplateDefinition.Origin.REALM_DB, MessageChannel.EMAIL, null, null, null, List.of("link")));
        var missing = assertThrows(MessageDeliveryException.class,
            () -> adapter(declared, List.of(sender)).send(emailRequest("auth.magic-link", Map.of())));
        assertEquals(MessageDeliveryException.Reason.TEMPLATE_PARAMETER_MISSING, missing.reason());
        assertTrue(missing.getMessage().contains("[link]"));

        var nullValue = new java.util.HashMap<String, Object>();
        nullValue.put("link", null);
        assertEquals(MessageDeliveryException.Reason.TEMPLATE_PARAMETER_MISSING,
            assertThrows(MessageDeliveryException.class,
                () -> adapter(declared, List.of(sender)).send(emailRequest("auth.magic-link", nullValue))).reason(),
            "a null value counts as missing, not as an untyped NullPointerException");

        EmailTemplateResolver providerTemplate = (realm, key) -> Optional.of(new EmailTemplateDefinition(key, null, null,
            null, EmailTemplateDefinition.Origin.REALM_DB, MessageChannel.EMAIL, null, "tmpl-1", null, List.of()));
        assertEquals(MessageDeliveryException.Reason.PROVIDER_TEMPLATE_UNSUPPORTED,
            assertThrows(MessageDeliveryException.class,
                () -> adapter(providerTemplate, List.of(sender)).send(emailRequest("k", Map.of()))).reason());

        assertTrue(sender.delivered.isEmpty(), "nothing refused reaches the sender");
    }

    @Test
    void emailAdapterWithoutASingleSenderIsUnavailable() {
        var none = adapter(new DefaultEmailTemplateResolver(null), List.of());
        assertEquals(MessageDeliveryException.Reason.DELIVERY_UNAVAILABLE,
            assertThrows(MessageDeliveryException.class,
                () -> none.send(emailRequest("framework-test", Map.of("name", "x", "realm", "y")))).reason());
        var two = adapter(new DefaultEmailTemplateResolver(null), List.of(new CapturingSender(), new CapturingSender()));
        assertThrows(MessageDeliveryException.class, () -> two.send(emailRequest("framework-test", Map.of())));
    }

    private record FixedAdapter(MessageChannel channel, String providerId) implements MessageChannelAdapter {
        @Override public DeliveryReceipt send(MessageRequest request) {
            return new DeliveryReceipt(providerId, channel, "m-1", DeliveryStatus.QUEUED, Instant.EPOCH, request.idempotencyKey());
        }
    }

    @Test
    void defaultDeliveryDispatchesToTheOneAdapterForTheChannel() {
        var delivery = new ChannelMessageDelivery(instanceOf(List.of(
            new FixedAdapter(MessageChannel.EMAIL, "mail"), new FixedAdapter(MessageChannel.SMS, "sms-a"))));
        var sms = new MessageRequest(null, new MessageRecipient(MessageChannel.SMS, "+15550100"), "k", null, null, null, "i");
        assertEquals("sms-a", delivery.send(sms).providerId());

        var whatsapp = new MessageRequest(null, new MessageRecipient(MessageChannel.WHATSAPP, "+15550100"), "k", null, null, null, null);
        assertEquals(MessageDeliveryException.Reason.NO_ADAPTER,
            assertThrows(MessageDeliveryException.class, () -> delivery.send(whatsapp)).reason());

        var ambiguous = new ChannelMessageDelivery(instanceOf(List.of(
            new FixedAdapter(MessageChannel.SMS, "sms-a"), new FixedAdapter(MessageChannel.SMS, "sms-b"))));
        var refused = assertThrows(MessageDeliveryException.class, () -> ambiguous.send(sms));
        assertEquals(MessageDeliveryException.Reason.AMBIGUOUS_ADAPTER, refused.reason());
        assertTrue(refused.getMessage().contains("sms-a") && refused.getMessage().contains("sms-b"));
    }

    @Test
    void requestsAndRecipientsDoNotLogSecretsOrAddresses() {
        var request = new MessageRequest(null, new MessageRecipient(MessageChannel.SMS, "+15550100"), "auth.magic-link",
            Map.of("link", "https://x.test/#token=secret"), null, null, null);
        assertFalse(request.toString().contains("secret"));
        assertFalse(request.toString().contains("+15550100"));
        assertThrows(IllegalArgumentException.class, () -> new MessageRecipient(MessageChannel.SMS, " "));
        assertThrows(IllegalArgumentException.class,
            () -> new MessageRequest(null, request.recipient(), " ", null, null, null, null));
    }
}

package com.e2eq.framework.mail;

import com.e2eq.framework.config.PostMarkConfig;
import com.e2eq.framework.model.messaging.MessageChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The receipt path fails closed; the legacy deliver() path keeps its behaviour. */
class PostmarkEmailSenderReceiptTest {

    private static PostMarkConfig config(String apiKey, String from) {
        return new PostMarkConfig() {
            @Override public Optional<String> apiKey() { return Optional.ofNullable(apiKey); }
            @Override public Optional<String> defaultFromEmailAddress() { return Optional.ofNullable(from); }
            @Override public Optional<String> defaultToEmailAddress() { return Optional.empty(); }
        };
    }

    /** Answers every send with one fixed status and body, and counts sends. */
    private static final class FixedHttpClient extends HttpClient {
        final int status;
        final String body;
        final AtomicInteger sends = new AtomicInteger();
        FixedHttpClient(int status, String body) { this.status = status; this.body = body; }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends.incrementAndGet();
            return (HttpResponse<T>) new HttpResponse<String>() {
                @Override public int statusCode() { return status; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                @Override public String body() { return body; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private static PostmarkEmailSender sender(HttpClient client, PostMarkConfig config) {
        PostmarkEmailSender sender = new PostmarkEmailSender(client);
        sender.objectMapper = new ObjectMapper();
        sender.postMarkConfig = config;
        return sender;
    }

    private static RenderedEmail email() {
        return RenderedEmail.builder().to(List.of("user@example.test")).subject("Sign in").textBody("link").build();
    }

    @Test
    void missingApiKeyOrSenderIsRefusedOnTheReceiptPathButSkippedOnTheLegacyPath() {
        var client = new FixedHttpClient(200, "{}");

        var noKey = sender(client, config(null, "noreply@example.test"));
        assertEquals(MessageDeliveryException.Reason.DELIVERY_UNAVAILABLE,
            assertThrows(MessageDeliveryException.class, () -> noKey.deliverWithReceipt(email())).reason());
        assertDoesNotThrow(() -> noKey.deliver(email()), "deliver() still skips, unchanged for existing callers");

        var noFrom = sender(client, config("key", null));
        assertEquals(MessageDeliveryException.Reason.DELIVERY_UNAVAILABLE,
            assertThrows(MessageDeliveryException.class, () -> noFrom.deliverWithReceipt(email())).reason());

        assertEquals(0, client.sends.get());
    }

    @Test
    void acceptedSendReturnsPostmarksMessageId() {
        var client = new FixedHttpClient(200, "{\"ErrorCode\":0,\"MessageID\":\"b7bc2f4a-e38e-4336-af7d-e6c392c2f817\"}");

        DeliveryReceipt receipt = sender(client, config("key", "noreply@example.test")).deliverWithReceipt(email());

        assertEquals("postmark", receipt.providerId());
        assertEquals(MessageChannel.EMAIL, receipt.channel());
        assertEquals("b7bc2f4a-e38e-4336-af7d-e6c392c2f817", receipt.providerMessageId());
        assertEquals(DeliveryStatus.QUEUED, receipt.status());
        assertEquals(1, client.sends.get());
    }

    @Test
    void rejectedSendIsADeliveryFailure() {
        var sender = sender(new FixedHttpClient(422, "{\"ErrorCode\":300,\"Message\":\"Invalid 'To' address: 'user@example.test'\"}"),
            config("key", "noreply@example.test"));
        var refused = assertThrows(MessageDeliveryException.class, () -> sender.deliverWithReceipt(email()));
        assertEquals(MessageDeliveryException.Reason.DELIVERY_UNAVAILABLE, refused.reason());
        assertTrue(refused.getMessage().contains("422"));
        assertFalse(refused.getMessage().contains("user@example.test"), "the provider's error body is not repeated");
        assertNull(refused.getCause());
        var legacy = assertThrows(IllegalStateException.class, () -> sender.deliver(email()));
        assertTrue(legacy.getMessage().contains("422"), "deliver() keeps its exception and message");
    }
}

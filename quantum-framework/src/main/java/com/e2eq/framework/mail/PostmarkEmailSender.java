package com.e2eq.framework.mail;

import com.e2eq.framework.config.PostMarkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@ApplicationScoped
public class PostmarkEmailSender implements EmailDeliveryService {

    private static final String POSTMARK_SEND_EMAIL_URL = "https://api.postmarkapp.com/email";

    private final HttpClient httpClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    PostMarkConfig postMarkConfig;

    public PostmarkEmailSender() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build());
    }

    PostmarkEmailSender(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void deliver(RenderedEmail email) {
        Objects.requireNonNull(email, "Rendered email is required.");

        String apiKey = normalize(postMarkConfig.apiKey().orElse(null));
        if (apiKey == null) {
            Log.info("Skipping email delivery because Postmark API key is not configured.");
            return;
        }

        String from = normalize(email.getFrom());
        if (from == null) {
            from = normalize(postMarkConfig.defaultFromEmailAddress().orElse(null));
        }
        if (from == null) {
            Log.info("Skipping email delivery because no from address is configured.");
            return;
        }

        String to = joinEmails(email.getTo());
        if (to == null) {
            throw new IllegalArgumentException("At least one email recipient is required.");
        }
        if (email.getSubject() == null || email.getSubject().isBlank()) {
            throw new IllegalArgumentException("Rendered email subject is required.");
        }
        if ((email.getHtmlBody() == null || email.getHtmlBody().isBlank())
            && (email.getTextBody() == null || email.getTextBody().isBlank())) {
            throw new IllegalArgumentException("Rendered email body is required.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("From", from);
        payload.put("To", to);
        payload.put("Subject", email.getSubject());
        if (email.getHtmlBody() != null && !email.getHtmlBody().isBlank()) {
            payload.put("HtmlBody", email.getHtmlBody());
        }
        if (email.getTextBody() != null && !email.getTextBody().isBlank()) {
            payload.put("TextBody", email.getTextBody());
        }
        addIfPresent(payload, "Cc", joinEmails(email.getCc()));
        addIfPresent(payload, "Bcc", joinEmails(email.getBcc()));
        addIfPresent(payload, "ReplyTo", normalize(email.getReplyTo()));
        addIfPresent(payload, "MessageStream", normalize(email.getMessageStream()));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(POSTMARK_SEND_EMAIL_URL))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Postmark-Server-Token", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Postmark delivery failed with status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Postmark delivery failed.", e);
        }
    }

    private void addIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String joinEmails(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return null;
        }
        String joined = emails.stream()
            .map(this::normalize)
            .filter(value -> value != null)
            .collect(Collectors.joining(","));
        return joined.isBlank() ? null : joined;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

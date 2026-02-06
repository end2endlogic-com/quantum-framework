package com.e2eq.framework.api.tools;

import com.e2eq.framework.model.persistent.tools.InvocationConfig;
import com.e2eq.framework.model.persistent.tools.ToolProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Invokes an external REST endpoint using ToolProviderConfig and InvocationConfig.
 * Used by {@link com.e2eq.framework.api.tools.DefaultToolExecutor} for EXTERNAL_REST tools.
 */
@ApplicationScoped
public class ExternalRestInvoker {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Calls the external API and returns the response body as a map (or error details).
     *
     * @param provider  connection config (baseUrl, headers, timeout)
     * @param invocation method, path, bodyTemplate
     * @param parameters parameters for path/body substitution
     * @return result with statusCode, body map or error message
     */
    public ExternalRestResult invoke(ToolProviderConfig provider, InvocationConfig invocation, Map<String, Object> parameters) {
        ExternalRestResult result = new ExternalRestResult();
        if (provider == null || provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()) {
            result.statusCode = 500;
            result.errorMessage = "ToolProviderConfig baseUrl is required";
            return result;
        }
        String method = invocation != null && invocation.getMethod() != null ? invocation.getMethod().toUpperCase() : "GET";
        String path = invocation != null && invocation.getPath() != null ? invocation.getPath() : "";
        String pathSubstituted = substitute(path, parameters);
        String base = provider.getBaseUrl().trim();
        if (base.endsWith("/") && pathSubstituted.startsWith("/")) {
            base = base.substring(0, base.length() - 1);
        } else if (!base.endsWith("/") && !pathSubstituted.startsWith("/") && !pathSubstituted.isEmpty()) {
            base = base + "/";
        }
        String url = base + pathSubstituted;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(provider.getTimeoutMs() > 0 ? provider.getTimeoutMs() : 30_000));

            if (provider.getDefaultHeaders() != null) {
                for (Map.Entry<String, String> e : provider.getDefaultHeaders().entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                }
            }
            if (invocation != null && invocation.getHeaders() != null) {
                for (Map.Entry<String, String> e : invocation.getHeaders().entrySet()) {
                    builder.header(e.getKey(), e.getValue());
                }
            }

            String contentType = invocation != null && invocation.getContentType() != null ? invocation.getContentType() : "application/json";
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                String body = invocation != null && invocation.getBodyTemplate() != null
                    ? substitute(invocation.getBodyTemplate(), parameters)
                    : (parameters != null && !parameters.isEmpty() ? new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parameters) : "{}");
                builder.header("Content-Type", contentType);
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            result.statusCode = response.statusCode();
            if (response.body() != null && !response.body().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(response.body(), Map.class);
                    result.data = parsed;
                } catch (Exception e) {
                    result.data = Map.of("raw", response.body());
                }
            } else {
                result.data = Map.of();
            }
        } catch (Exception e) {
            result.statusCode = 500;
            result.errorMessage = e.getMessage();
            result.data = null;
        }
        return result;
    }

    private static String substitute(String template, Map<String, Object> parameters) {
        if (template == null || parameters == null) {
            return template != null ? template : "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = parameters.get(key);
            m.appendReplacement(sb, val != null ? Matcher.quoteReplacement(val.toString()) : "");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static class ExternalRestResult {
        public int statusCode;
        public Map<String, Object> data;
        public String errorMessage;
    }
}

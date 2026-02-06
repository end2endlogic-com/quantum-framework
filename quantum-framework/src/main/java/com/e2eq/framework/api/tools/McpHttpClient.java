package com.e2eq.framework.api.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP-based MCP client. Sends JSON-RPC 2.0 requests (tools/list, tools/call) via POST
 * to the MCP endpoint. Compatible with Streamable HTTP transport (single JSON response).
 */
@ApplicationScoped
public class McpHttpClient implements McpClient {

    private static final String JSON_RPC_VERSION = "2.0";
    private static final int TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public McpHttpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public List<McpToolDescriptor> listTools(String baseUrl, Map<String, String> authHeaders) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> request = Map.of(
                "jsonrpc", JSON_RPC_VERSION,
                "id", 1,
                "method", "tools/list"
            );
            String body = objectMapper.writeValueAsString(request);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(normalizeUrl(baseUrl)))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (authHeaders != null) {
                authHeaders.forEach(req::header);
            }
            HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                Log.warnf("MCP tools/list returned %d: %s", response.statusCode(), response.body());
                return List.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> json = objectMapper.readValue(response.body(), Map.class);
            if (json.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> err = (Map<String, Object>) json.get("error");
                Log.warnf("MCP tools/list error: %s", err.get("message"));
                return List.of();
            }
            Object result = json.get("result");
            if (result == null) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) result;
            Object toolsObj = resultMap.get("tools");
            if (toolsObj == null || !(toolsObj instanceof List)) {
                return List.of();
            }
            List<McpToolDescriptor> out = new ArrayList<>();
            for (Object t : (List<?>) toolsObj) {
                if (t instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tool = (Map<String, Object>) t;
                    String name = stringVal(tool.get("name"));
                    String description = stringVal(tool.get("description"));
                    Object inputSchema = tool.get("inputSchema");
                    if (name != null && !name.isBlank()) {
                        out.add(new McpToolDescriptor(name, description != null ? description : "", inputSchema));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            Log.warnf(e, "MCP listTools failed for %s", baseUrl);
            return List.of();
        }
    }

    @Override
    public McpCallResult callTool(String baseUrl, String toolName, Map<String, Object> arguments,
                                  Map<String, String> authHeaders) {
        if (baseUrl == null || baseUrl.isBlank() || toolName == null || toolName.isBlank()) {
            return McpCallResult.error("baseUrl and toolName required");
        }
        try {
            Map<String, Object> params = Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            );
            Map<String, Object> request = Map.of(
                "jsonrpc", JSON_RPC_VERSION,
                "id", 2,
                "method", "tools/call",
                "params", params
            );
            String body = objectMapper.writeValueAsString(request);
            HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(normalizeUrl(baseUrl)))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (authHeaders != null) {
                authHeaders.forEach(req::header);
            }
            HttpResponse<String> response = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return McpCallResult.error("HTTP " + response.statusCode() + ": " + response.body());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> json = objectMapper.readValue(response.body(), Map.class);
            if (json.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> err = (Map<String, Object>) json.get("error");
                String msg = stringVal(err.get("message"));
                return McpCallResult.error(msg != null ? msg : "MCP error");
            }
            Object result = json.get("result");
            if (result == null) {
                return McpCallResult.ok(List.of());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) result;
            Object contentObj = resultMap.get("content");
            if (contentObj instanceof List) {
                List<Map<String, Object>> content = new ArrayList<>();
                for (Object c : (List<?>) contentObj) {
                    if (c instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) c;
                        content.add(m);
                    }
                }
                return McpCallResult.ok(content);
            }
            return McpCallResult.ok(List.of(Map.<String, Object>of("type", "text", "text", result.toString())));
        } catch (Exception e) {
            Log.warnf(e, "MCP callTool %s failed for %s", toolName, baseUrl);
            return McpCallResult.error(e.getMessage() != null ? e.getMessage() : "call failed");
        }
    }

    private static String normalizeUrl(String baseUrl) {
        String u = baseUrl.trim();
        if (u.endsWith("/")) {
            return u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String stringVal(Object o) {
        if (o == null) return null;
        return o.toString();
    }
}

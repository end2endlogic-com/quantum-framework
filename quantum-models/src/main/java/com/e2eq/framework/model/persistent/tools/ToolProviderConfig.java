package com.e2eq.framework.model.persistent.tools;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.IndexOptions;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

import java.util.Map;

/**
 * Connection details for an external tool source (REST, MCP, gRPC).
 * Referenced by {@link ToolDefinition#getProviderRef()} for EXTERNAL_REST and EXTERNAL_MCP tools.
 */
@RegisterForReflection
@Entity(value = "toolProviderConfigs", useDiscriminator = false)
public class ToolProviderConfig {

    @Id
    private ObjectId id;

    /** Unique ref within realm (e.g. helix-control-plane, salesforce-mcp). Used by ToolDefinition.providerRef. */
    @Indexed(options = @IndexOptions(unique = true))
    private String refName;

    /** Display name. */
    private String name;

    /** REST, MCP, GRPC. */
    private ProviderType providerType = ProviderType.REST;

    /** Base URL for the provider. */
    private String baseUrl;

    /** Authentication (bearer, api_key, etc.). */
    private AuthConfig auth;

    /** Default headers for requests. */
    private Map<String, String> defaultHeaders;

    private int timeoutMs = 30_000;
    private int maxRetries = 2;
    private boolean enabled = true;

    /** MCP: sse, stdio, streamable-http. */
    private String mcpTransport;

    /** If true, allow discovery/import of tools from this provider. */
    private boolean autoDiscoverTools;

    /** Timestamp of last tool sync (for MCP). */
    private String lastDiscoverySync;

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getRefName() {
        return refName;
    }

    public void setRefName(String refName) {
        this.refName = refName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMcpTransport() {
        return mcpTransport;
    }

    public void setMcpTransport(String mcpTransport) {
        this.mcpTransport = mcpTransport;
    }

    public boolean isAutoDiscoverTools() {
        return autoDiscoverTools;
    }

    public void setAutoDiscoverTools(boolean autoDiscoverTools) {
        this.autoDiscoverTools = autoDiscoverTools;
    }

    public String getLastDiscoverySync() {
        return lastDiscoverySync;
    }

    public void setLastDiscoverySync(String lastDiscoverySync) {
        this.lastDiscoverySync = lastDiscoverySync;
    }
}

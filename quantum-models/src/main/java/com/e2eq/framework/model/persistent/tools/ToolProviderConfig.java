package com.e2eq.framework.model.persistent.tools;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.IndexOptions;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.types.ObjectId;

/**
 * External tool provider configuration (REST, MCP). Agents reference these by refName to call
 * external APIs or MCP servers. Framework entity; apps (e.g. psa-app) use via
 * ToolProviderConfigRepo and expose REST.
 */
@RegisterForReflection
@Entity(value = "tool_provider_configs", useDiscriminator = false)
public class ToolProviderConfig {

    public static final String PROVIDER_TYPE_REST = "REST";
    public static final String PROVIDER_TYPE_MCP = "MCP";

    @Id
    private ObjectId id;

    @Indexed(options = @IndexOptions(unique = true))
    private String refName;

    private String displayName;
    private String providerType = PROVIDER_TYPE_REST;
    private String baseUrl;

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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}

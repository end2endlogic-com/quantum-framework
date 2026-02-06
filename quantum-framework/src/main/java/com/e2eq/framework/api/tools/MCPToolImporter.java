package com.e2eq.framework.api.tools;

import com.e2eq.framework.model.persistent.morphia.ToolDefinitionRepo;
import com.e2eq.framework.model.persistent.tools.ProviderType;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import com.e2eq.framework.model.persistent.tools.ToolProviderConfig;
import com.e2eq.framework.model.persistent.tools.ToolType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Imports tools from an external MCP server into the realm's tool registry as ToolDefinitions (EXTERNAL_MCP).
 * Uses {@link McpClient} to list tools and persists them with providerRef set to the provider's refName.
 */
@ApplicationScoped
public class MCPToolImporter {

    private static final String SOURCE = "mcp-imported";

    @Inject
    McpClient mcpClient;

    @Inject
    ToolDefinitionRepo toolDefinitionRepo;

    /**
     * Connects to the provider (MCP server), discovers tools, and optionally registers them as ToolDefinitions.
     *
     * @param realm    realm id
     * @param provider provider config (baseUrl, mcpTransport, etc.)
     * @param persist  if true, save imported tools to the realm
     * @return list of tool definitions (created and optionally persisted)
     */
    public List<ToolDefinition> importFromProvider(String realm, ToolProviderConfig provider, boolean persist) {
        if (realm == null || realm.isBlank() || provider == null || provider.getProviderType() != ProviderType.MCP) {
            return List.of();
        }
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return List.of();
        }
        Map<String, String> headers = buildAuthHeaders(provider);
        List<McpClient.McpToolDescriptor> descriptors = mcpClient.listTools(baseUrl, headers);
        List<ToolDefinition> out = new ArrayList<>();
        String providerRef = provider.getRefName();
        for (McpClient.McpToolDescriptor d : descriptors) {
            if (d.name() == null || d.name().isBlank()) continue;
            String refName = sanitizeRefName(d.name());
            if (persist && toolDefinitionRepo.findByRefName(realm, refName).isPresent()) {
                continue;
            }
            ToolDefinition t = new ToolDefinition();
            t.setRefName(refName);
            t.setName(d.name());
            t.setDescription(d.description() != null ? d.description() : "");
            t.setToolType(ToolType.EXTERNAL_MCP);
            t.setProviderRef(providerRef);
            t.setSource(SOURCE);
            t.setEnabled(true);
            t.setHasSideEffects(true);
            if (d.inputSchema() != null) {
                t.setInputJsonSchema(toJsonString(d.inputSchema()));
            }
            if (persist) {
                toolDefinitionRepo.save(realm, t);
            }
            out.add(t);
        }
        return out;
    }

    private static Map<String, String> buildAuthHeaders(ToolProviderConfig provider) {
        if (provider.getDefaultHeaders() != null && !provider.getDefaultHeaders().isEmpty()) {
            return provider.getDefaultHeaders();
        }
        return Map.of();
    }

    private static String sanitizeRefName(String name) {
        if (name == null) return "";
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String toJsonString(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return o.toString();
        }
    }
}

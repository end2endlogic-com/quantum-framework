package com.e2eq.framework.api.agent.llm;

import com.e2eq.framework.model.persistent.tools.ParameterDefinition;
import com.e2eq.framework.model.persistent.tools.ToolDefinition;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts {@link ToolDefinition} to provider-agnostic {@link ProviderToolSchema}
 * for LLM APIs (name, description, input_schema).
 */
@ApplicationScoped
public class ToolFormatConverter {

    /**
     * Converts a list of tool definitions to provider tool schemas.
     */
    public List<ProviderToolSchema> toProviderSchemas(List<ToolDefinition> tools) {
        if (tools == null) {
            return List.of();
        }
        return tools.stream()
            .map(this::toProviderSchema)
            .collect(Collectors.toList());
    }

    /**
     * Converts a single tool definition to provider schema.
     */
    public ProviderToolSchema toProviderSchema(ToolDefinition tool) {
        if (tool == null) {
            return null;
        }
        String name = tool.getRefName() != null ? tool.getRefName() : tool.getName();
        String description = tool.getDescription() != null ? tool.getDescription() : tool.getLongDescription();
        if (description == null) {
            description = name;
        }
        Map<String, Object> inputSchema = buildInputSchema(tool.getInputSchema(), tool.getInputJsonSchema());
        return new ProviderToolSchema(name, description, inputSchema);
    }

    private Map<String, Object> buildInputSchema(Map<String, ParameterDefinition> inputSchema, String inputJsonSchema) {
        if (inputJsonSchema != null && !inputJsonSchema.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(inputJsonSchema, Map.class);
                return parsed;
            } catch (Exception e) {
                // fall through to built schema
            }
        }
        if (inputSchema != null && !inputSchema.isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new java.util.ArrayList<>();
            for (Map.Entry<String, ParameterDefinition> e : inputSchema.entrySet()) {
                props.put(e.getKey(), paramToJsonSchema(e.getValue()));
                if (e.getValue() != null && e.getValue().isRequired()) {
                    required.add(e.getKey());
                }
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        }
        return Map.of("type", "object", "properties", Map.of());
    }

    private Map<String, Object> paramToJsonSchema(ParameterDefinition p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p != null && p.getType() != null) {
            m.put("type", p.getType());
            if (p.getDescription() != null) {
                m.put("description", p.getDescription());
            }
        } else {
            m.put("type", "string");
        }
        return m;
    }
}

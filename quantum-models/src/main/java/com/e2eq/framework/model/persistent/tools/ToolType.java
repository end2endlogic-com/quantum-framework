package com.e2eq.framework.model.persistent.tools;

/**
 * Tool invocation type. Used by {@link ToolDefinition}.
 */
public enum ToolType {
    QUANTUM_API,
    QUANTUM_QUERY,
    HELIX,
    EXTERNAL_REST,
    EXTERNAL_MCP,
    GRPC,
    FUNCTION
}

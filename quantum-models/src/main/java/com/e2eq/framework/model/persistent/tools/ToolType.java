package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Type of tool invocation backend. Used by {@link ToolDefinition} and the tool execution layer
 * to route execution.
 *
 * @see ToolDefinition
 */
@RegisterForReflection
public enum ToolType {

    /** Internal Quantum REST endpoint or service method. */
    QUANTUM_API,

    /** Query gateway invocation (plan, find, save, delete, deleteMany, rootTypes). */
    QUANTUM_QUERY,

    /** Helix control plane endpoint. */
    HELIX,

    /** External REST API. */
    EXTERNAL_REST,

    /** Imported from external MCP server. */
    EXTERNAL_MCP,

    /** gRPC service call. */
    GRPC,

    /** In-process function (e.g. GraalVM polyglot). */
    FUNCTION
}

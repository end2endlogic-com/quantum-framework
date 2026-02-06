package com.e2eq.framework.model.persistent.tools;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Type of external tool provider. Used by {@link ToolProviderConfig}.
 */
@RegisterForReflection
public enum ProviderType {

    /** External REST API. */
    REST,

    /** MCP (Model Context Protocol) server. */
    MCP,

    /** gRPC service. */
    GRPC
}

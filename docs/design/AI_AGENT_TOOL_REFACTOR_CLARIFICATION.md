# Agent & Tool Architecture

## Overview

The Quantum agent/tool layer exposes the QueryGateway as a set of tools callable by AI models (Claude, GPT, Gemini) via two transports:

- **MCP** (Model Context Protocol) — JSON-RPC at `/mcp`, used by Claude Desktop, Cursor, and custom MCP clients
- **REST** — HTTP/JSON at `/api/agent/*`, used by any HTTP client or LLM integration

Tools are **code-defined** via `@Tool` annotations (Quarkus MCP Server extension). There is no database-backed tool registry; tool definitions live in Java source code and are discovered at startup via classpath scanning.

Agents are **configuration envelopes** — they pair an LLM reference with a system prompt and a tool filter. Agents are persisted in MongoDB and managed via `/api/agent/config/*`.

-----

## Architecture

```
┌──────────────────────────────────────────────────┐
│  AI Client (Claude Desktop / Cursor / Custom)    │
└─────────────────────┬────────────────────────────┘
                      │ MCP (JSON-RPC)
                      ▼
┌──────────────────────────────────────────────────┐
│  McpGatewayTools (@Tool annotations)             │
│  12 tools: query_rootTypes, query_plan,          │
│  query_find, query_count, query_save,            │
│  query_delete, query_deleteMany,                 │
│  query_import_*, query_export                    │
└─────────────────────┬────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────┐
│  AgentExecuteHandler (switch-based router)       │
│  Converts args → typed Request DTOs              │
│  Delegates to QueryGatewayResource               │
└─────────────────────┬────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────┐
│  QueryGatewayResource (generic CRUDL engine)     │
│  BIAPI query syntax, realm-scoped, multi-tenant  │
│  Works on any @Entity in the system              │
└─────────────────────┬────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────┐
│  Domain Entities + Morphia + MongoDB             │
└──────────────────────────────────────────────────┘
```

The REST path (`/api/agent/*`) provides the same capabilities:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/agent/tools` | Tool discovery (6 core tools, filtered by tenant config) |
| `GET /api/agent/schema` | List all entity types |
| `GET /api/agent/schema/{rootType}` | JSON Schema for one entity type |
| `GET /api/agent/query-hints` | Query grammar, examples, tips |
| `GET /api/agent/permission-hints` | Permission check API summary |
| `POST /api/agent/execute` | Execute a tool by name with arguments |

-----

## Agent Entity

An Agent is a realm-scoped configuration stored in MongoDB (`agents` collection):

```java
@Entity(value = "agents", useDiscriminator = false)
public class Agent {
    @Id ObjectId id;
    @Indexed(unique = true) String refName;
    String name;
    String llmConfigRef;           // which LLM to use (references a secret/config)
    List<PromptStep> context;      // ordered system/user prompt steps
    List<String> enabledTools;     // tool name filter (e.g. ["query_find", "query_plan"])
}
```

`PromptStep` defines ordered prompt context:

```java
public class PromptStep {
    int order;
    String role;     // "system", "user"
    String content;  // prompt text
}
```

**CRUD API** at `/api/agent/config`:

| Method | Path | Action |
|--------|------|--------|
| GET | `/list?realm=X` | List all agents |
| GET | `/{refName}?realm=X` | Get by refName |
| GET | `/id/{id}?realm=X` | Get by ObjectId |
| POST | `/?realm=X` | Create/update |
| DELETE | `/{refName}?realm=X` | Delete by refName |
| DELETE | `/id/{id}?realm=X` | Delete by id |

-----

## Tool Surface

### How tools are defined

Tools are Java methods annotated with `@Tool` (from `io.quarkiverse.mcp.server`). The Quarkus MCP Server extension discovers them at startup and exposes them via the `/mcp` endpoint.

**McpGatewayTools.java** — 12 `@Tool` methods wrapping QueryGateway operations:

| Tool | Operation |
|------|-----------|
| `query_rootTypes` | List all entity types |
| `query_plan` | Plan a query (FILTER vs AGGREGATION) |
| `query_find` | Execute a find query |
| `query_count` | Count matching records |
| `query_save` | Create or update an entity |
| `query_delete` | Delete by id |
| `query_deleteMany` | Delete by query |
| `query_import_analyze` | Analyze CSV for import |
| `query_import_rows` | Import rows from CSV |
| `query_import_commit` | Commit import session |
| `query_import_cancel` | Cancel import session |
| `query_export` | Export entities to CSV |

**McpOntologyTools.java** — 2 `@Tool` methods for ontology exploration (`query_relationships`, `query_predicates`).

### How tools are routed

`AgentExecuteHandler.execute(String tool, Map<String, Object> arguments)` — a switch statement that:
1. Converts `arguments` to a typed Request DTO via `ObjectMapper.convertValue()`
2. Calls the corresponding `QueryGatewayResource` method
3. Returns a JAX-RS `Response`

### How tools are discovered

`AgentToolsProvider` returns a hardcoded list of 6 core gateway tools for the `GET /api/agent/tools` endpoint. When a realm is provided and `TenantAgentConfig.enabledTools` is set, the list is filtered to only those tools.

-----

## Tenant Configuration

Per-tenant agent behavior is configured via `application.properties`:

```properties
# Restrict tenant "acme" to read-only tools
quantum.agent.tenant.acme.enabledTools=query_rootTypes,query_plan,query_find,query_count

# Cap query results
quantum.agent.tenant.acme.maxFindLimit=100

# Optional: run tool calls under a specific user's security context
quantum.agent.tenant.acme.runAsUserId=agent-service-user
```

`PropertyTenantAgentConfigResolver` reads these properties and returns a `TenantAgentConfig` for the given realm. The config is used by `AgentToolsProvider` to filter the tool list at discovery time.

-----

## External Tool Providers

`ToolProviderConfig` (MongoDB collection `tool_provider_configs`) stores connection details for external REST or MCP tool providers:

```java
@Entity(value = "tool_provider_configs", useDiscriminator = false)
public class ToolProviderConfig {
    @Id ObjectId id;
    @Indexed(unique = true) String refName;
    String displayName;
    String providerType;  // "REST" or "MCP"
    String baseUrl;
}
```

Managed via `ToolProviderConfigRepo` with standard save/find/delete operations.

-----

## Key Files

| File | Module | Purpose |
|------|--------|---------|
| `McpGatewayTools.java` | quantum-mcp-server | 12 @Tool methods (MCP entry point) |
| `McpOntologyTools.java` | quantum-mcp-server | 2 ontology @Tool methods |
| `AgentExecuteHandler.java` | quantum-mcp-server | Switch-based tool router |
| `AgentResource.java` | quantum-mcp-server | REST discovery + execute at `/api/agent/*` |
| `AgentToolsProvider.java` | quantum-mcp-server | Tool list for discovery |
| `AgentConfigResource.java` | quantum-framework | Agent CRUD at `/api/agent/config/*` |
| `Agent.java` | quantum-models | Agent entity |
| `PromptStep.java` | quantum-models | Prompt step embedded in Agent |
| `AgentRepo.java` | quantum-morphia-repos | Agent persistence |
| `TenantAgentConfig.java` | quantum-mcp-server | Per-tenant config model |
| `PropertyTenantAgentConfigResolver.java` | quantum-mcp-server | Config from properties |
| `ToolProviderConfig.java` | quantum-models | External provider config entity |
| `ToolProviderConfigRepo.java` | quantum-morphia-repos | External provider persistence |
| `QueryGatewayResource.java` | quantum-framework | The actual CRUDL engine |

-----

## What Was Removed

The following classes were removed as dead code. They were designed but never implemented — no repository existed, and no code path loaded or referenced them:

- `ToolDefinition.java` — `@Entity("toolDefinitions")` with 30+ fields for database-backed tool metadata
- `ToolType.java` — enum (QUANTUM_API, QUANTUM_QUERY, HELIX, EXTERNAL_REST, EXTERNAL_MCP, GRPC, FUNCTION)
- `InvocationConfig.java` — HTTP invocation config embedded in ToolDefinition
- `ToolExample.java` — few-shot examples embedded in ToolDefinition
- `ErrorSemantics.java` — error handling config embedded in ToolDefinition
- `ParameterDefinition.java` — parameter schema embedded in ToolDefinition

**Rationale**: Tools are code-defined via `@Tool` annotations. The MCP protocol handles tool discovery, schema, and invocation natively. A database-backed tool registry adds complexity without value — if a tool exists, it exists as code; if it doesn't exist as code, it can't be invoked.

-----

## Related Documents

- [AI Agent Integration (user guide)](../quantum-docs/src/docs/asciidoc/user-guide/ai-agent-integration.adoc) — end-user documentation
- [MCP Server and Client (user guide)](../quantum-docs/src/docs/asciidoc/user-guide/mcp-server-and-client.adoc) — MCP setup and Claude/Cursor integration
- [Tool as LLM Context (design)](TOOL-AS-LLM-CONTEXT-DESIGN.md) — terminology decision: Agent = LLM + context, Tool = callable capability
- [AI Agent Integration (design)](AI_AGENT_INTEGRATION_DESIGN.md) — original integration design

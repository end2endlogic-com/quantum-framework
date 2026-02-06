# Agent & Tool Framework — Implementation Design

## Overview

This document specifies the agent and tool abstractions for the Quantum Framework. These are first-class Quantum entities — realm-scoped, secured via the existing permission model, persisted in MongoDB via Morphia, and exposed through standard Quantum REST APIs.

The framework enables three orchestration modes:

- **Fully declarative workflows** — JSON workflow definitions, deterministic execution
- **Fully agentic** — LLM-driven tool selection and execution
- **Hybrid** — deterministic workflows with agentic steps, or agents that produce workflow definitions

-----

## Domain Model

### Entity Hierarchy

All entities extend Quantum’s `BasePersistentModel`, inheriting:

- `dataDomain` (realm scoping)
- `refName` (unique reference within realm)
- `creationIdentity` / `lastUpdateIdentity` (audit)
- Standard lifecycle fields

```
BasePersistentModel
├── ToolDefinition
├── ToolProviderConfig
├── AgentDefinition
├── AgentConversation
├── AgentConversationTurn
├── WorkflowDefinition
├── WorkflowInstance
├── ConditionBinding
└── StepRegistration
```

-----

## Tool Definition

### Purpose

A tool is an atomic capability that can be invoked by an agent (via LLM reasoning) or by a workflow step (via declarative configuration). Tools wrap existing Quantum APIs, Helix endpoints, or external services.

### Data Model: `ToolDefinition`

```java
@Entity("toolDefinitions")
@FunctionalMapping(area = "ai", domain = "tools")
public class ToolDefinition extends BasePersistentModel {

    // Identity
    private String name;                    // Semantic name: "search_customers", "solve_vrp"
    private String description;             // Natural language for LLM tool selection
    private String category;                // Grouping: "quantum.crud", "helix.optimization", "external"
    private List<String> tags;              // Freeform tags for filtering/search

    // Schema
    private Map<String, ParameterDefinition> inputSchema;   // Parameters with types + descriptions
    private Map<String, ParameterDefinition> outputSchema;  // Return shape
    private String inputJsonSchema;         // Full JSON Schema (auto-generated or manual override)
    private String outputJsonSchema;        // Full JSON Schema for output

    // Invocation
    private ToolType toolType;              // QUANTUM_API, HELIX, EXTERNAL_REST, EXTERNAL_MCP, GRPC, FUNCTION
    private InvocationConfig invocation;    // How to actually call this tool
    private String providerRef;             // Reference to ToolProviderConfig for connection details

    // Behavioral hints
    private boolean hasSideEffects;         // true = write operation, false = read-only
    private boolean idempotent;             // Safe to retry?
    private String estimatedLatency;        // "fast" (<100ms), "medium" (<5s), "slow" (>5s)
    private String costHint;                // "free", "low", "high" — for agent prioritization
    private boolean requiresConfirmation;   // Agent should confirm with user before calling

    // Error handling
    private Map<Integer, ErrorSemantics> errorHandling;  // HTTP status → retry/clarify/fail
    private int defaultRetryCount;
    private String defaultRetryBackoff;     // "none", "linear", "exponential"

    // Availability
    private List<String> availableAs;       // ["tool", "workflowStep", "mcpTool"]
    private boolean enabled;
    private String securityUri;             // Permission URI for access control

    // Documentation
    private String longDescription;         // Detailed usage notes for developers
    private List<ToolExample> examples;     // Example invocations for LLM few-shot
}
```

### Supporting Types

```java
public class ParameterDefinition {
    private String name;
    private String type;                    // "string", "number", "boolean", "object", "array"
    private String description;             // Natural language for LLM: "Filter by order status, one of PENDING, ACTIVE, CLOSED"
    private boolean required;
    private Object defaultValue;
    private List<String> enumValues;        // Constrained values if applicable
    private String jsonSchemaRef;           // Reference to full JSON Schema for complex types
}

public enum ToolType {
    QUANTUM_API,        // Internal Quantum REST endpoint or service method
    QUANTUM_QUERY,      // Query gateway invocation
    HELIX,              // Helix control plane endpoint
    EXTERNAL_REST,      // External REST API
    EXTERNAL_MCP,       // Imported from external MCP server
    GRPC,               // gRPC service call
    FUNCTION            // In-process function (GraalVM polyglot capable)
}

public class InvocationConfig {
    private String method;                  // HTTP method or invocation style
    private String path;                    // Endpoint path or function reference
    private Map<String, String> headers;    // Static headers
    private String bodyTemplate;            // Template for request body using ${param.name} expressions
    private String responseMapping;         // JSONPath or expression to extract result
    private String contentType;             // "application/json", "application/grpc", etc.
}

public class ErrorSemantics {
    private String action;                  // "retry", "clarify", "fail", "fallback"
    private String message;                 // Human-readable error description for LLM
    private String fallbackToolRef;         // Alternative tool to try on this error
}

public class ToolExample {
    private String scenario;                // "Find customers in California"
    private Map<String, Object> input;      // {"state": "CA", "status": "active"}
    private Map<String, Object> output;     // {"customers": [...]}
}
```

### Tool Provider Configuration: `ToolProviderConfig`

Manages connection details for external tool sources, separate from individual tool definitions:

```java
@Entity("toolProviderConfigs")
@FunctionalMapping(area = "ai", domain = "toolProviders")
public class ToolProviderConfig extends BasePersistentModel {

    private String name;                    // "helix-control-plane", "salesforce-mcp"
    private ProviderType providerType;      // REST, MCP, GRPC, QUANTUM_INTERNAL
    private String baseUrl;                 // Base URL for the provider
    private AuthConfig auth;                // Authentication configuration
    private Map<String, String> defaultHeaders;
    private int timeoutMs;
    private int maxRetries;
    private boolean enabled;

    // MCP-specific
    private String mcpTransport;            // "sse", "stdio", "streamable-http"
    private boolean autoDiscoverTools;      // If true, poll MCP server for tool list
    private String lastDiscoverySync;       // Timestamp of last tool sync
}

public class AuthConfig {
    private String authType;                // "none", "bearer", "api_key", "oauth2", "mtls"
    private String secretRef;               // Reference to secret vault for credentials
    private String tokenEndpoint;           // For OAuth2 flows
    private String headerName;              // For API key auth
}
```

-----

## Auto-Generation from Quantum Type Registry

### Purpose

Quantum already has a type registry with full schema information for every persistent entity. Tools should be auto-generated from this registry so that when a developer registers a new entity type, CRUD tools are automatically available to agents.

### Auto-Generated Tool Set Per Type

For each registered Quantum type (e.g., `Order`, `Customer`, `Vehicle`), generate:

|Generated Tool|Name Pattern           |Type         |
|--------------|-----------------------|-------------|
|Search/Query  |`search_{pluralName}`  |QUANTUM_QUERY|
|Get by ID     |`get_{singularName}`   |QUANTUM_API  |
|Create        |`create_{singularName}`|QUANTUM_API  |
|Update        |`update_{singularName}`|QUANTUM_API  |
|Delete        |`delete_{singularName}`|QUANTUM_API  |
|Count         |`count_{pluralName}`   |QUANTUM_QUERY|

### Generation Metadata Annotation

Developers can annotate entity classes to control tool generation:

```java
@Entity("orders")
@ToolGeneration(
    singularName = "order",
    pluralName = "orders",
    description = "Customer purchase orders with line items and fulfillment status",
    category = "order_management",
    excludeOperations = { Operation.DELETE },  // Don't generate delete tool
    searchableFields = { "status", "customerId", "createdDate", "totalAmount" },
    tags = { "oms", "fulfillment" }
)
public class Order extends BasePersistentModel {
    // ...
}
```

### Generation Process

At application startup (or on-demand refresh):

1. Scan all `@Entity` classes with `@ToolGeneration` annotation
1. For each, generate `ToolDefinition` instances using the annotation metadata + Morphia schema introspection
1. Persist generated tools with a `source = "auto-generated"` marker
1. Do not overwrite tools that have been manually customized (check `source` field)

The query gateway tool uses the existing ANTLR query DSL, so the generated `search_*` tools accept filter expressions in Quantum’s native query language.

-----

## Agent Definition

### Purpose

An agent is an LLM-backed decision-maker that uses tools to accomplish goals. It has a persona (system prompt), a set of available tools, and an execution loop.

### Data Model: `AgentDefinition`

```java
@Entity("agentDefinitions")
@FunctionalMapping(area = "ai", domain = "agents")
public class AgentDefinition extends BasePersistentModel {

    // Identity
    private String name;                        // "fulfillment-planning-agent"
    private String displayName;                 // "Fulfillment Planning Agent"
    private String description;                 // What this agent does

    // LLM Configuration
    private LLMConfig llmConfig;                // Model, temperature, token limits

    // Persona
    private String systemPrompt;                // Core system prompt defining persona and focus
    private List<String> systemPromptRefs;      // References to reusable prompt fragments
    private String responseFormat;              // "text", "json", "structured" — output format hint

    // Tool Access
    private List<String> toolRefs;              // Explicit tool references by refName
    private List<String> toolCategories;        // Include all tools in these categories
    private List<String> toolTags;              // Include all tools with these tags
    private List<String> excludedToolRefs;      // Explicitly exclude specific tools
    private int maxToolsInContext;              // Limit tools sent to LLM (context window management)

    // Sub-Agent Access
    private List<String> delegateAgentRefs;     // Other agents this agent can invoke as tools
    private boolean canCreateWorkflows;         // Can this agent author workflow definitions?

    // Execution Configuration
    private ExecutionConfig executionConfig;     // Loop limits, timeout, durability

    // Memory & Context
    private MemoryConfig memoryConfig;          // Conversation retention, context window strategy

    // Security
    private String securityUri;                 // Permission URI
    private String principalRef;                // Service account / identity this agent acts as
    private List<String> allowedRealms;         // Realms this agent can operate in (empty = current realm only)

    // Behavior
    private boolean requiresApproval;           // Human approval before executing side-effect tools
    private List<GuardrailConfig> guardrails;   // Safety constraints
    private boolean enabled;
}
```

### Supporting Types

```java
public class LLMConfig {
    private String provider;                // "anthropic", "openai", "helix" (for Helix reasoning)
    private String model;                   // "claude-sonnet-4-20250514", "gpt-4o", etc.
    private double temperature;             // 0.0 - 1.0
    private int maxOutputTokens;
    private int maxInputTokens;             // Context window budget
    private String apiKeySecretRef;         // Reference to secret vault
    private String baseUrl;                 // Custom endpoint if needed
    private Map<String, Object> extraParams;// Provider-specific parameters
}

public class ExecutionConfig {
    private int maxIterations;              // Max observe/think/act loops (default: 10)
    private int timeoutSeconds;             // Overall execution timeout
    private boolean durable;                // If true, execute via Restate for crash recovery
    private String restateServiceRef;       // Restate service name if durable
    private boolean parallelToolCalls;      // Allow multiple tool calls per iteration
    private int maxParallelCalls;           // Limit concurrent tool invocations
}

public class MemoryConfig {
    private String strategy;                // "full", "sliding_window", "summary", "none"
    private int maxConversationTurns;       // Sliding window size
    private int maxContextTokens;           // Token budget for conversation history
    private boolean persistConversation;    // Save conversation to MongoDB
    private String summaryAgentRef;         // Agent used to summarize long conversations
}

public class GuardrailConfig {
    private String type;                    // "max_cost", "no_delete", "require_confirmation", "content_filter"
    private Map<String, Object> parameters; // Type-specific configuration
}
```

-----

## Agent Execution

### Execution Loop

The agent execution follows an observe → think → act → observe loop:

```
┌──────────────────────────────────────────┐
│             Agent Execution              │
│                                          │
│  1. Build context (system prompt +       │
│     conversation history + tool list)    │
│                                          │
│  2. Call LLM with context                │
│     ┌─────────────────────────┐          │
│     │ LLM Response            │          │
│     │ ├── Text response       │──► Done  │
│     │ └── Tool call request   │──► 3     │
│     └─────────────────────────┘          │
│                                          │
│  3. Validate tool call                   │
│     ├── Check permissions                │
│     ├── Check guardrails                 │
│     └── Request confirmation if needed   │
│                                          │
│  4. Execute tool(s)                      │
│     ├── Route through ToolExecutor       │
│     └── Collect results                  │
│                                          │
│  5. Append results to conversation       │
│     └── Go to step 2 (loop)             │
│                                          │
│  Exit: text response, max iterations,    │
│        timeout, or error                 │
└──────────────────────────────────────────┘
```

### Service Interface

```java
public interface AgentExecutionService {

    /**
     * Execute an agent synchronously (short-lived, non-durable).
     * Suitable for simple query/response patterns.
     */
    AgentResponse execute(AgentExecutionRequest request);

    /**
     * Execute an agent via Restate (durable, crash-recoverable).
     * Suitable for long-running or multi-tool workflows.
     */
    String executeDurable(AgentExecutionRequest request);  // Returns execution ID

    /**
     * Stream agent execution (SSE). Each iteration yields partial results.
     */
    Multi<AgentStreamEvent> executeStreaming(AgentExecutionRequest request);

    /**
     * Continue an existing conversation with a new user message.
     */
    AgentResponse continueConversation(String conversationId, String userMessage);

    /**
     * Get status of a durable execution.
     */
    AgentExecutionStatus getExecutionStatus(String executionId);
}
```

### Request / Response Models

```java
public class AgentExecutionRequest {
    private String agentRef;                // Which agent definition to use
    private String userMessage;             // The user's input
    private String conversationId;          // Existing conversation ID (null = new conversation)
    private Map<String, Object> context;    // Additional context variables
    private String realmId;                 // Target realm (defaults to current)
    private String principalId;             // Acting principal (defaults to current user)
}

public class AgentResponse {
    private String conversationId;
    private String responseText;            // Agent's text response
    private List<ToolInvocation> toolCalls; // Tools that were invoked
    private int iterationsUsed;
    private int tokensUsed;
    private AgentStatus status;             // COMPLETED, MAX_ITERATIONS, TIMEOUT, ERROR, AWAITING_APPROVAL
    private Map<String, Object> outputContext;  // Structured output if applicable
}

public class ToolInvocation {
    private String toolRef;
    private Map<String, Object> input;
    private Map<String, Object> output;
    private long durationMs;
    private ToolInvocationStatus status;    // SUCCESS, ERROR, SKIPPED, PERMISSION_DENIED
    private String errorMessage;
}

public class AgentStreamEvent {
    private AgentStreamEventType type;      // THINKING, TOOL_CALL_START, TOOL_CALL_END, TEXT_DELTA, DONE, ERROR
    private String content;                 // Text delta or tool info
    private ToolInvocation toolInvocation;  // Present for tool events
    private int iteration;
}
```

-----

## Tool Execution

### Tool Executor

The `ToolExecutor` is the central routing layer. It resolves a tool reference, validates permissions, and dispatches the call to the appropriate backend.

```java
public interface ToolExecutor {

    /**
     * Execute a tool by reference name with given parameters.
     * Resolves tool type and routes to appropriate handler.
     */
    ToolResult execute(String toolRef, Map<String, Object> parameters, ExecutionContext context);

    /**
     * Execute multiple tools in parallel.
     */
    List<ToolResult> executeParallel(List<ToolCall> calls, ExecutionContext context);
}
```

### Routing Logic

```
ToolExecutor.execute(toolRef, params, context)
    │
    ├── Load ToolDefinition by refName
    ├── Check permissions: securityUri against current principal
    ├── Validate input against inputSchema
    │
    ├── Route by toolType:
    │   ├── QUANTUM_API     → invoke Quantum REST endpoint internally (no HTTP, direct service call)
    │   ├── QUANTUM_QUERY   → invoke query gateway with type + filter params
    │   ├── HELIX           → call Helix control plane via HTTP/gRPC
    │   ├── EXTERNAL_REST   → call external API using ToolProviderConfig
    │   ├── EXTERNAL_MCP    → call via MCP client using ToolProviderConfig
    │   ├── GRPC            → call via gRPC stub
    │   └── FUNCTION        → invoke in-process function (GraalVM polyglot)
    │
    ├── Map response using responseMapping
    ├── Validate output against outputSchema
    └── Return ToolResult
```

### Execution Context

```java
public class ExecutionContext {
    private String realmId;
    private String principalId;
    private String correlationId;
    private String traceId;
    private String agentRef;                // Which agent is invoking (null if workflow)
    private String workflowInstanceId;      // Which workflow is invoking (null if agent)
    private boolean durable;                // If true, tool call is journaled by Restate
}
```

### Tool Result

```java
public class ToolResult {
    private String toolRef;
    private ToolResultStatus status;        // SUCCESS, ERROR, TIMEOUT, PERMISSION_DENIED, VALIDATION_ERROR
    private Map<String, Object> data;       // Successful result
    private String errorMessage;            // Error details
    private int httpStatus;                 // Original HTTP status if applicable
    private long durationMs;
    private Map<String, String> metadata;   // Response headers, pagination info, etc.
}
```

-----

## Conversation Persistence

### Purpose

Agent conversations are persisted for continuity, audit, and context. Each conversation belongs to a realm and principal.

### Data Model: `AgentConversation`

```java
@Entity("agentConversations")
@FunctionalMapping(area = "ai", domain = "conversations")
public class AgentConversation extends BasePersistentModel {

    private String agentRef;                // Which agent definition
    private String principalId;             // Which user
    private String title;                   // Auto-generated or user-provided
    private ConversationStatus status;      // ACTIVE, COMPLETED, ARCHIVED
    private int turnCount;
    private int totalTokensUsed;
    private String summary;                 // Compressed summary for long conversations
    private Map<String, Object> sharedContext;  // Context variables carried across turns
}

@Entity("agentConversationTurns")
@FunctionalMapping(area = "ai", domain = "conversationTurns")
public class AgentConversationTurn extends BasePersistentModel {

    private String conversationId;          // Parent conversation
    private int turnIndex;                  // Order within conversation
    private String role;                    // "user", "assistant", "system", "tool"
    private String content;                 // Message content
    private List<ToolInvocation> toolCalls; // Tools invoked in this turn
    private int tokensUsed;
    private long durationMs;
}
```

-----

## MCP Integration

### Exporting Tools via MCP

Quantum exposes its tool registry to external MCP clients:

```java
@MCPServer
public class QuantumMCPServer {

    @Inject ToolRegistry toolRegistry;
    @Inject SecurityContext securityContext;

    /**
     * MCP tools/list — returns tools visible to the authenticated principal.
     */
    public List<MCPToolDefinition> listTools() {
        String realmId = securityContext.getEffectiveRealm();
        String principalId = securityContext.getPrincipalId();

        return toolRegistry.findAccessibleTools(realmId, principalId)
            .stream()
            .filter(t -> t.getAvailableAs().contains("mcpTool"))
            .map(this::toMCPToolDefinition)
            .toList();
    }

    /**
     * MCP tools/call — validates permissions and executes.
     */
    public MCPToolResult callTool(String name, Map<String, Object> arguments) {
        ExecutionContext ctx = buildContext();
        ToolResult result = toolExecutor.execute(name, arguments, ctx);
        return toMCPToolResult(result);
    }
}
```

### Importing Tools from External MCP Servers

```java
public class MCPToolImporter {

    /**
     * Connect to an external MCP server, discover tools, and register them
     * as ToolDefinitions with toolType = EXTERNAL_MCP.
     */
    public List<ToolDefinition> importFromMCPServer(ToolProviderConfig provider) {
        MCPClient client = MCPClient.connect(provider.getBaseUrl(), provider.getMcpTransport());
        List<MCPToolDefinition> externalTools = client.listTools();

        return externalTools.stream()
            .map(mcp -> toToolDefinition(mcp, provider.getRefName()))
            .map(toolRepo::save)
            .toList();
    }
}
```

-----

## REST API Design

All endpoints follow Quantum’s standard `BaseResource` pattern with full CRUD + query support.

### Tool APIs

```
# CRUD
POST   /api/v1/ai/tools                    — Create tool definition
GET    /api/v1/ai/tools/{refName}           — Get tool by refName
PUT    /api/v1/ai/tools/{refName}           — Update tool definition
DELETE /api/v1/ai/tools/{refName}           — Delete tool definition

# Query (via Quantum query gateway)
POST   /api/v1/ai/tools/query               — Query tools with filters
GET    /api/v1/ai/tools/count               — Count tools matching filter

# Execution
POST   /api/v1/ai/tools/{refName}/execute   — Execute a tool directly
POST   /api/v1/ai/tools/{refName}/validate  — Validate input against schema (dry run)

# Auto-generation
POST   /api/v1/ai/tools/generate            — Trigger auto-generation from type registry
GET    /api/v1/ai/tools/generated            — List auto-generated tools

# MCP import
POST   /api/v1/ai/tools/import/mcp          — Import tools from external MCP server
```

### Agent APIs

```
# CRUD
POST   /api/v1/ai/agents                    — Create agent definition
GET    /api/v1/ai/agents/{refName}           — Get agent by refName
PUT    /api/v1/ai/agents/{refName}           — Update agent definition
DELETE /api/v1/ai/agents/{refName}           — Delete agent definition

# Query
POST   /api/v1/ai/agents/query              — Query agents with filters

# Execution
POST   /api/v1/ai/agents/{refName}/execute           — Execute agent (synchronous)
POST   /api/v1/ai/agents/{refName}/execute/durable    — Execute agent (durable via Restate)
POST   /api/v1/ai/agents/{refName}/execute/stream     — Execute agent (SSE streaming)

# Conversations
GET    /api/v1/ai/conversations                       — List conversations for current user
GET    /api/v1/ai/conversations/{id}                  — Get conversation with turns
POST   /api/v1/ai/conversations/{id}/continue         — Continue conversation
DELETE /api/v1/ai/conversations/{id}                  — Delete conversation

# Tool resolution
GET    /api/v1/ai/agents/{refName}/tools              — List resolved tools for this agent
```

### Tool Provider APIs

```
# CRUD
POST   /api/v1/ai/tool-providers                     — Create provider config
GET    /api/v1/ai/tool-providers/{refName}            — Get provider
PUT    /api/v1/ai/tool-providers/{refName}            — Update provider
DELETE /api/v1/ai/tool-providers/{refName}            — Delete provider

# Operations
POST   /api/v1/ai/tool-providers/{refName}/test       — Test connectivity
POST   /api/v1/ai/tool-providers/{refName}/discover    — Discover tools (MCP)
```

-----

## Tool Resolution for Agents

When an agent executes, its available tool set is resolved dynamically:

```java
public class ToolResolver {

    public List<ToolDefinition> resolveToolsForAgent(AgentDefinition agent, String realmId) {
        Set<ToolDefinition> tools = new LinkedHashSet<>();

        // 1. Explicit tool references
        if (agent.getToolRefs() != null) {
            tools.addAll(toolRepo.findByRefNames(agent.getToolRefs()));
        }

        // 2. Category-based inclusion
        if (agent.getToolCategories() != null) {
            tools.addAll(toolRepo.findByCategories(agent.getToolCategories()));
        }

        // 3. Tag-based inclusion
        if (agent.getToolTags() != null) {
            tools.addAll(toolRepo.findByTags(agent.getToolTags()));
        }

        // 4. Remove excluded tools
        if (agent.getExcludedToolRefs() != null) {
            tools.removeIf(t -> agent.getExcludedToolRefs().contains(t.getRefName()));
        }

        // 5. Filter by realm access and permissions
        tools.removeIf(t -> !securityService.hasAccess(t.getSecurityUri(), realmId));

        // 6. Filter by enabled status
        tools.removeIf(t -> !t.isEnabled());

        // 7. Add delegate agents as tools
        if (agent.getDelegateAgentRefs() != null) {
            for (String agentRef : agent.getDelegateAgentRefs()) {
                tools.add(agentToToolAdapter(agentRef));
            }
        }

        // 8. Trim to context window budget if needed
        if (agent.getMaxToolsInContext() > 0 && tools.size() > agent.getMaxToolsInContext()) {
            tools = prioritizeTools(tools, agent.getMaxToolsInContext());
        }

        return new ArrayList<>(tools);
    }
}
```

-----

## LLM Integration Layer

### Purpose

Abstract LLM provider differences so agent definitions are provider-agnostic.

### Interface

```java
public interface LLMClient {

    /**
     * Send a chat completion request with tool definitions.
     */
    LLMResponse chat(LLMRequest request);

    /**
     * Stream a chat completion response.
     */
    Multi<LLMStreamEvent> chatStream(LLMRequest request);
}

public class LLMRequest {
    private String model;
    private List<ChatMessage> messages;             // Conversation history
    private List<LLMToolDefinition> tools;          // Available tools in LLM format
    private double temperature;
    private int maxTokens;
    private String responseFormat;                  // "text", "json_object"
    private Map<String, Object> extraParams;
}

public class LLMResponse {
    private String content;                         // Text response (null if tool call)
    private List<LLMToolCall> toolCalls;            // Requested tool invocations
    private LLMUsage usage;                         // Token counts
    private String stopReason;                      // "end_turn", "tool_use", "max_tokens"
}

public class LLMToolCall {
    private String id;                              // Provider-assigned call ID
    private String toolName;                        // Matches ToolDefinition.name
    private Map<String, Object> arguments;          // Parsed arguments
}
```

### Provider Implementations

```java
// Provider registry — resolved from LLMConfig.provider
public interface LLMClientFactory {
    LLMClient create(LLMConfig config);
}

// Implementations
public class AnthropicLLMClient implements LLMClient { ... }
public class OpenAILLMClient implements LLMClient { ... }
public class HelixReasoningClient implements LLMClient { ... }  // Helix as LLM-like interface
```

### Tool Format Conversion

Each LLM provider has its own tool/function calling format. The conversion layer translates `ToolDefinition` to provider-specific format:

```java
public interface ToolFormatConverter {
    LLMToolDefinition convert(ToolDefinition tool);
}

// Anthropic format: tools with input_schema
public class AnthropicToolConverter implements ToolFormatConverter { ... }

// OpenAI format: functions with parameters
public class OpenAIToolConverter implements ToolFormatConverter { ... }
```

-----

## Security Integration

### Permission Model

Tools and agents integrate with Quantum’s existing security URI and RETE rule engine:

```
# Tool permissions
ai://tools/{category}/{name}/execute        — Permission to execute a tool
ai://tools/{category}/{name}/read           — Permission to view tool definition
ai://tools/{category}/{name}/write          — Permission to modify tool definition

# Agent permissions
ai://agents/{name}/execute                  — Permission to run an agent
ai://agents/{name}/read                     — Permission to view agent definition
ai://agents/{name}/write                    — Permission to modify agent definition

# Conversation permissions
ai://conversations/own/read                 — View own conversations
ai://conversations/own/delete               — Delete own conversations
ai://conversations/all/read                 — Admin: view all conversations in realm
```

### Agent Identity

When an agent executes tools, it acts as a principal within the realm. The identity chain:

```
User (initiator)
  └── Agent (acts as configured principalRef OR inherits user identity)
        └── Tool (executes with agent's effective identity)
              └── Quantum API (standard security check against effective principal)
```

If `agent.principalRef` is set, the agent acts as that service account (useful for agents that need elevated permissions). If not set, the agent inherits the invoking user’s identity (tools are limited to what the user can do).

### Guardrails

Guardrails provide additional safety constraints beyond permissions:

```java
public class GuardrailEvaluator {

    public GuardrailResult evaluate(ToolInvocation invocation, AgentDefinition agent) {
        for (GuardrailConfig guardrail : agent.getGuardrails()) {
            switch (guardrail.getType()) {
                case "max_cost":
                    // Check cumulative cost of tool calls in this execution
                    break;
                case "no_delete":
                    // Block any tool with DELETE semantics
                    break;
                case "require_confirmation":
                    // Pause execution and request human approval
                    break;
                case "content_filter":
                    // Validate tool input/output against content policy
                    break;
                case "realm_boundary":
                    // Ensure tool doesn't access data outside allowed realms
                    break;
            }
        }
    }
}
```

-----

## Workflow Integration

### Tools as Workflow Steps

The same `ToolDefinition` used by agents is also used by workflow steps. The `StepRegistration` entity binds a tool to the workflow vocabulary:

```java
@Entity("stepRegistrations")
@FunctionalMapping(area = "ai", domain = "workflowSteps")
public class StepRegistration extends BasePersistentModel {

    private String name;                    // Step name in workflow vocabulary
    private String toolRef;                 // Reference to ToolDefinition
    private String description;             // Step-specific description
    private Map<String, String> inputMapping;   // Default input mappings
    private Map<String, String> outputMapping;  // Default output mappings
    private boolean enabled;
}
```

### Agent as Workflow Step

An agent can be invoked as a step within a workflow:

```json
{
  "name": "evaluate_exception",
  "handlerRef": "quantum.ai.invokeAgent",
  "input": {
    "agentRef": "exception-handling-agent",
    "prompt": "Order ${orderId} failed validation: ${validationErrors}. Determine resolution.",
    "context": {
      "orderId": "${input.orderId}",
      "validationErrors": "${validate.errors}"
    }
  },
  "dependsOn": ["validate_orders"],
  "condition": "${validate.errors.length > 0}"
}
```

### Workflow as Agent Tool

An agent can trigger a workflow as a tool:

```json
{
  "refName": "execute_fulfillment_workflow",
  "name": "execute_fulfillment_workflow",
  "description": "Triggers the standard order fulfillment workflow for a set of order IDs",
  "toolType": "FUNCTION",
  "invocation": {
    "path": "quantum.workflows.execute",
    "bodyTemplate": {
      "workflowRef": "order-fulfillment-standard",
      "input": { "orderIds": "${orderIds}" }
    }
  },
  "hasSideEffects": true,
  "requiresConfirmation": true
}
```

-----

## Quarkus Module Structure

```
quantum-ai/
├── quantum-ai-core/                   — Domain model, interfaces, tool resolution
│   ├── model/
│   │   ├── ToolDefinition.java
│   │   ├── AgentDefinition.java
│   │   ├── AgentConversation.java
│   │   ├── AgentConversationTurn.java
│   │   ├── ToolProviderConfig.java
│   │   └── StepRegistration.java
│   ├── service/
│   │   ├── ToolExecutor.java
│   │   ├── ToolResolver.java
│   │   ├── AgentExecutionService.java
│   │   ├── ToolAutoGenerator.java
│   │   └── GuardrailEvaluator.java
│   └── llm/
│       ├── LLMClient.java
│       ├── LLMClientFactory.java
│       └── ToolFormatConverter.java
│
├── quantum-ai-anthropic/              — Anthropic Claude integration
│   ├── AnthropicLLMClient.java
│   └── AnthropicToolConverter.java
│
├── quantum-ai-openai/                 — OpenAI integration
│   ├── OpenAILLMClient.java
│   └── OpenAIToolConverter.java
│
├── quantum-ai-mcp/                    — MCP server + client
│   ├── QuantumMCPServer.java
│   ├── MCPToolImporter.java
│   └── MCPClientFactory.java
│
├── quantum-ai-restate/                — Durable agent execution via Restate
│   ├── DurableAgentExecutionService.java
│   └── RestateWorkflowExecutor.java
│
└── quantum-ai-rest/                   — REST API endpoints
    ├── ToolResource.java
    ├── AgentResource.java
    ├── ConversationResource.java
    └── ToolProviderResource.java
```

-----

## Configuration

### Application Properties

```properties
# LLM defaults
quantum.ai.default-provider=anthropic
quantum.ai.default-model=claude-sonnet-4-20250514
quantum.ai.default-temperature=0.7
quantum.ai.default-max-tokens=4096

# Agent execution defaults
quantum.ai.agent.default-max-iterations=10
quantum.ai.agent.default-timeout-seconds=120
quantum.ai.agent.default-durable=false

# Tool auto-generation
quantum.ai.tools.auto-generate=true
quantum.ai.tools.auto-generate-on-startup=true

# MCP server
quantum.ai.mcp.enabled=true
quantum.ai.mcp.transport=sse
quantum.ai.mcp.path=/mcp

# Restate integration
quantum.ai.restate.enabled=false
quantum.ai.restate.endpoint=http://restate:8080

# Guardrails
quantum.ai.guardrails.max-tool-calls-per-execution=50
quantum.ai.guardrails.max-cost-per-execution=1.00
```

-----

## Implementation Priority

### Phase 1: Foundation

1. `ToolDefinition` entity + CRUD API
1. `ToolProviderConfig` entity + CRUD API
1. `ToolExecutor` with QUANTUM_API and QUANTUM_QUERY routing
1. Tool auto-generation from `@ToolGeneration` annotations
1. Basic `ToolResource` REST endpoints

### Phase 2: Agent Core

1. `AgentDefinition` entity + CRUD API
1. `LLMClient` interface + Anthropic implementation
1. `ToolResolver` — dynamic tool set resolution
1. `AgentExecutionService` — synchronous execution loop
1. `AgentConversation` / `AgentConversationTurn` persistence
1. `AgentResource` REST endpoints with streaming support

### Phase 3: External Integration

1. MCP server adapter (export tools)
1. MCP client (import external tools)
1. `ToolProviderConfig` with external REST and gRPC support
1. OpenAI LLM client implementation

### Phase 4: Durability & Workflows

1. Restate integration for durable agent execution
1. `StepRegistration` and workflow step binding
1. Dynamic workflow executor
1. Agent-as-workflow-step and workflow-as-agent-tool patterns

### Phase 5: Advanced

1. Delegate agent (agent-calls-agent) support
1. Advanced guardrails and approval workflows
1. Conversation summarization and memory management
1. Helix reasoning client (Helix as LLM-like interface)
1. GraalVM polyglot function tools

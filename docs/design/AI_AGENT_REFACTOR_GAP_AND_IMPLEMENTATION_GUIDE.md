# AI Agent Refactor: Gap Analysis and Implementation Guide

## 1. Purpose

This document compares the **AI Agent Tool Refactor Clarification** (`AI_AGENT_TOOL_REFACTOR_CLARIFICATION.md`) — treated as the **target vision** — to the **AI Agent Integration Design** (`AI_AGENT_INTEGRATION_DESIGN.md`) and to **current implementation**. It identifies gaps and provides a design and implementation guide to close them.

**Reference documents:**
- **REFACTOR** (target): `docs/design/AI_AGENT_TOOL_REFACTOR_CLARIFICATION.md`
- **INTEGRATION** (superseded for vision): `docs/design/AI_AGENT_INTEGRATION_DESIGN.md`

---

## 2. Vision Summary

### 2.1 REFACTOR Vision (Target)

The refactor specifies a full **Agent & Tool Framework** with:

- **First-class entities** (realm-scoped, secured, persisted via Morphia):
  - `ToolDefinition` — rich tool schema (name, description, input/output schema, invocation, error handling, behavioral hints)
  - `ToolProviderConfig` — connection details for external tool sources (REST, MCP, gRPC)
  - `AgentDefinition` — LLM config, system prompt, tool access (refs/categories/tags), execution/memory/guardrails
  - `AgentConversation` / `AgentConversationTurn` — conversation persistence
  - `WorkflowDefinition` / `WorkflowInstance` / `StepRegistration` / `ConditionBinding` — workflow integration

- **Three orchestration modes:** fully declarative workflows, fully agentic (LLM-driven tools), hybrid.

- **Tool types:** QUANTUM_API, QUANTUM_QUERY, HELIX, EXTERNAL_REST, EXTERNAL_MCP, GRPC, FUNCTION.

- **Auto-generation:** Tools from type registry via `@ToolGeneration` (e.g. `search_orders`, `get_order`, `create_order`, etc.).

- **Execution:** `ToolExecutor` (routes by tool type), `ToolResolver` (resolves tools for an agent: refs + categories + tags − exclusions, permissions, delegate agents), `AgentExecutionService` (execute / executeDurable / executeStreaming / continueConversation).

- **REST surface:** `/api/v1/ai/tools`, `/api/v1/ai/agents`, `/api/v1/ai/tool-providers`, `/api/v1/ai/conversations` with full CRUD, query, execute, validate, generate, import.

- **MCP:** In-app `QuantumMCPServer` (export tools), `MCPToolImporter` (import from external MCP).

- **Module structure:** `quantum-ai` with core, anthropic, openai, mcp, restate, rest.

### 2.2 INTEGRATION Design (Superseded for Vision)

- **Single integration point:** Query Gateway only; six fixed tools: `query_rootTypes`, `query_plan`, `query_find`, `query_save`, `query_delete`, `query_deleteMany`.
- **Agent layer:** GET `/api/agent/tools`, GET `/api/agent/schema`, GET `/api/agent/schema/{rootType}`, POST `/api/agent/execute`.
- **Tenant config:** `AgentTenantConfig` (runAsUserId, enabledTools, defaultRealm, limits).
- **No** persisted ToolDefinition or AgentDefinition entities; no LLM execution loop; no conversation persistence; MCP as standalone bridge calling `/api/agent/*` and `/api/query/*`.

### 2.3 Current Implementation (What Exists)

| Area | Implemented | Notes |
|------|-------------|--------|
| **Agent API (gateway tools)** | Yes | `AgentResource` @ `/api/agent`: tools, schema, schema/{rootType}, execute, query-hints, permission-hints |
| **Six gateway tools** | Yes | `AgentToolsProvider`: fixed list; filtered by tenant `enabledTools` |
| **Execute** | Yes | `AgentExecuteHandler`: dispatch to QueryGatewayResource; runAs via `RunAsPrincipalResolver`; tenant limits (e.g. maxFindLimit) |
| **Tenant config** | Yes | `TenantAgentConfig` (realm, runAsUserId, enabledTools, maxFindLimit); `TenantAgentConfigResolver` (property-based impl) |
| **Schema** | Yes | `SchemaService`: rootTypes (via gateway), `getSchemaForRootType(rootType)` from Morphia EntityModel |
| **Agent config (CRUD)** | Yes | `AgentConfigResource` @ `/api/agents`; `Agent` entity (refName, name, llmConfigRef, context=PromptStep list, enabledTools); `AgentRepo` |
| **Agent model** | Partial | `Agent` has context (PromptStep), llmConfigRef, enabledTools; **does not** extend framework base (e.g. UnversionedBaseModel); no toolRefs/categories/tags, no ExecutionConfig, MemoryConfig, guardrails, securityUri, delegateAgentRefs |
| **ToolDefinition** | No | No persisted tool definitions; tools are hard-coded in AgentToolsProvider |
| **ToolProviderConfig** | No | Not implemented |
| **ToolExecutor** | No | Only direct dispatch in AgentExecuteHandler for the six gateway tools |
| **ToolResolver** | No | No dynamic resolution from ToolDefinition; tenant + agent enabledTools only |
| **Agent execution loop** | No | No LLM call, no observe-think-act loop, no AgentExecutionService |
| **Conversations** | No | No AgentConversation / AgentConversationTurn persistence or APIs |
| **REST path** | Different | Current: `/api/agent`, `/api/agents`. REFACTOR: `/api/v1/ai/tools`, `/api/v1/ai/agents`, etc. |
| **MCP in-app** | No | No QuantumMCPServer or MCPToolImporter in framework |
| **Auto-generation** | No | No @ToolGeneration, no ToolAutoGenerator |
| **Workflows** | No | No StepRegistration, agent-as-step, workflow-as-tool |

---

## 3. Gap Analysis (REFACTOR vs Current)

### 3.1 Domain Model

| REFACTOR | Current | Gap |
|----------|---------|-----|
| ToolDefinition entity (refName, name, description, inputSchema, outputSchema, toolType, invocation, providerRef, hasSideEffects, errorHandling, availableAs, securityUri, etc.) | None | **Missing.** Tools are fixed in AgentToolsProvider. |
| ToolProviderConfig entity | None | **Missing.** |
| AgentDefinition extends BasePersistentModel; systemPrompt, systemPromptRefs, toolRefs, toolCategories, toolTags, excludedToolRefs, maxToolsInContext; delegateAgentRefs; LLMConfig, ExecutionConfig, MemoryConfig, GuardrailConfig; securityUri, principalRef, allowedRealms; requiresApproval, guardrails | Agent entity with refName, name, llmConfigRef, context (PromptStep), enabledTools only; does not extend base persistent model | **Partial.** Agent exists but is a slim config; no tool resolution model, no execution/memory/guardrail config, no securityUri/principalRef, no delegate agents. Agent not aligned with REFACTOR’s AgentDefinition. |
| AgentConversation / AgentConversationTurn | None | **Missing.** |
| StepRegistration, WorkflowDefinition, WorkflowInstance, ConditionBinding | None | **Missing.** (Out of scope for initial refactor phases if desired.) |

### 3.2 Tool Layer

| REFACTOR | Current | Gap |
|----------|---------|-----|
| ToolExecutor: load ToolDefinition, check permissions, validate input, route by toolType (QUANTUM_API, QUANTUM_QUERY, HELIX, EXTERNAL_*), map response | AgentExecuteHandler: only maps six fixed tool names to QueryGatewayResource | **Missing.** No ToolDefinition, no routing by tool type, no QUANTUM_API/HELIX/EXTERNAL_* path. |
| ToolResolver: resolve tools for agent (toolRefs + categories + tags − exclusions, permissions, delegate agents, maxToolsInContext) | Tenant enabledTools + optional agent enabledTools filter on fixed list | **Missing.** No ToolDefinition set, no category/tag/ref resolution. |
| Tools from registry: CRUD, query, execute by refName, validate, auto-generate, import MCP | Only GET tools (fixed list) and POST execute (by fixed name) | **Missing.** No tool CRUD, no query, no execute by refName, no validate, no generate, no MCP import. |

### 3.3 Agent Execution

| REFACTOR | Current | Gap |
|----------|---------|-----|
| AgentExecutionService: execute, executeDurable, executeStreaming, continueConversation, getExecutionStatus | None | **Missing.** No LLM loop, no streaming, no durable execution, no continue. |
| Observe → think → act loop; LLM with tools; ToolExecutor for tool calls | Only POST /api/agent/execute for single gateway tool call | **Missing.** No in-process LLM integration. |
| LLMClient + LLMClientFactory; provider-specific ToolFormatConverter | None | **Missing.** |
| Request/response: AgentExecutionRequest, AgentResponse (conversationId, responseText, toolCalls, iterationsUsed, tokensUsed, status); AgentStreamEvent | ExecuteRequest (tool, arguments, sessionId, traceId); response = raw gateway response | **Partial.** Session/trace present; no conversation or agent response DTOs. |

### 3.4 REST API

| REFACTOR | Current | Gap |
|----------|---------|-----|
| `/api/v1/ai/tools` CRUD, query, count, execute by refName, validate, generate, generated, import/mcp | `/api/agent/tools` (GET only, fixed list), `/api/agent/execute` (POST, tool by name) | Path and surface: no tool CRUD/query/execute by refName/validate/generate/import. |
| `/api/v1/ai/agents` CRUD by refName, query, execute/durable/stream by refName, GET agents/{refName}/tools | `/api/agents` CRUD by id + by-ref query param; no execute, no tools sub-resource | Path and surface: refName in path; agent execute and tools resolution not exposed. |
| `/api/v1/ai/tool-providers` CRUD, test, discover | None | **Missing.** |
| `/api/v1/ai/conversations` list, get, continue, delete | None | **Missing.** |

### 3.5 Security and Identity

| REFACTOR | Current | Gap |
|----------|---------|-----|
| Tool securityUri; agent securityUri, principalRef, allowedRealms; GuardrailEvaluator | @FunctionalMapping(integration/query); runAs via TenantAgentConfig | **Partial.** No securityUri on tools/agents, no guardrails, no principalRef/allowedRealms on agent. |

### 3.6 MCP and Auto-Generation

| REFACTOR | Current | Gap |
|----------|---------|-----|
| QuantumMCPServer (listTools from registry, callTool via ToolExecutor) | None | **Missing.** |
| MCPToolImporter (import from external MCP → ToolDefinitions) | None | **Missing.** |
| @ToolGeneration on entities; ToolAutoGenerator; POST generate, GET generated | None | **Missing.** |

---

## 4. Design and Implementation Guide to Close Gaps

The following is a phased plan that favors the REFACTOR vision while reusing existing code where possible.

---

### Phase 1: Tool Definition and Tool Executor Foundation

**Goal:** Introduce persisted ToolDefinition and ToolExecutor that can serve both the existing six gateway tools and future tool types.

**Design decisions:**

1. **ToolDefinition entity**
   - Add `ToolDefinition` (and supporting types: ParameterDefinition, InvocationConfig, ErrorSemantics, ToolExample, ToolType enum) in `quantum-models` (or equivalent). Prefer extending the framework’s persistent base (e.g. UnversionedBaseModel or existing pattern) with realm/scoping and refName as in REFACTOR.
   - Collection name and functional mapping: e.g. `@FunctionalMapping(area = "ai", domain = "tools")` as in REFACTOR.
   - Fields: name, description, category, tags, inputSchema/outputSchema (or inputJsonSchema/outputJsonSchema), toolType, invocation, providerRef, hasSideEffects, idempotent, estimatedLatency, costHint, requiresConfirmation, errorHandling, defaultRetryCount, defaultRetryBackoff, availableAs, enabled, securityUri, longDescription, examples. Add a `source` field (e.g. `"manual"` | `"auto-generated"`) for Phase 3.

2. **Seeded “gateway” tools**
   - Seed or bootstrap six ToolDefinitions that mirror the current six gateway operations (query_rootTypes, query_plan, query_find, query_save, query_delete, query_deleteMany) with toolType QUANTUM_QUERY/QUANTUM_API as appropriate, so existing behavior is represented in the registry.

3. **ToolExecutor**
   - Introduce `ToolExecutor` interface: `ToolResult execute(String toolRef, Map<String, Object> parameters, ExecutionContext context)` (and optionally executeParallel).
   - Implementation: resolve ToolDefinition by refName (realm from context); check permission (securityUri); validate input against schema; switch on toolType. For QUANTUM_QUERY/QUANTUM_API, delegate to current AgentExecuteHandler logic (or inline gateway calls). Stub or no-op for HELIX, EXTERNAL_REST, EXTERNAL_MCP, GRPC, FUNCTION until later phases.
   - ExecutionContext: realmId, principalId, correlationId, traceId, agentRef, workflowInstanceId, durable (as in REFACTOR).

4. **Tool REST API**
   - New resource under `/api/v1/ai/tools` (or keep `/api/agent` and add `/api/agent/tools` as CRUD if path migration is deferred): POST (create), GET `/{refName}`, PUT `/{refName}`, DELETE `/{refName}`, POST `/query`, GET `/count`, POST `/{refName}/execute`, POST `/{refName}/validate`.
   - Execute: resolve realm from context, load ToolDefinition, call ToolExecutor.execute. Validate: validate arguments against inputSchema only.
   - Reuse existing permission model; add actions for ai/tools (read, write, execute) as in REFACTOR.

5. **Backward compatibility**
   - Keep existing GET `/api/agent/tools` and POST `/api/agent/execute` working: either (a) have them delegate to ToolDefinition registry (e.g. list tools where availableAs contains "gateway" or similar), or (b) keep current behavior and add a feature flag to switch to registry once seeded tools exist. Prefer (a) so one code path uses ToolExecutor.

**Deliverables:** ToolDefinition model + repo; seeded six tools; ToolExecutor (QUANTUM_* only); Tool REST CRUD + execute + validate; tests.

---

### Phase 2: Agent Definition Alignment and Tool Resolution

**Goal:** Align Agent with REFACTOR’s AgentDefinition (tool access model, execution config, security) and introduce ToolResolver; expose agent execute and tools list.

**Design decisions:**

1. **AgentDefinition model**
   - Evolve existing `Agent` entity toward `AgentDefinition`: add (or map) systemPrompt, systemPromptRefs, toolRefs, toolCategories, toolTags, excludedToolRefs, maxToolsInContext, delegateAgentRefs, executionConfig, memoryConfig, guardrails, securityUri, principalRef, allowedRealms, requiresApproval, responseFormat, canCreateWorkflows. Keep existing context (PromptStep) as one way to build system prompt if desired.
   - Ensure agent is realm-scoped and has refName; align with framework base if applicable.

2. **ToolResolver**
   - Implement `ToolResolver.resolveToolsForAgent(AgentDefinition agent, String realmId)`: union of toolRefs, toolCategories, toolTags; subtract excludedToolRefs; filter by permission and enabled; add delegate agents as tools; apply maxToolsInContext. Use ToolDefinition repo (and later agent-to-tool adapter for delegateAgentRefs).

3. **Agent execute and tools API**
   - GET `/api/v1/ai/agents/{refName}/tools`: return list of ToolDefinitions (or DTOs) from ToolResolver for that agent.
   - POST `/api/v1/ai/agents/{refName}/execute`: accept AgentExecutionRequest (userMessage, conversationId, context, realmId, principalId). For Phase 2, optional: only “single-shot” tool use (e.g. resolve tools, call LLM once with tools, execute one round of tool calls, return). Full loop can be Phase 3.

4. **Tenant config**
   - Keep TenantAgentConfig (runAsUserId, enabledTools, limits). Integrate with ToolResolver: e.g. treat tenant enabledTools as an additional filter when resolving tools for an agent in that realm (so existing behavior is preserved).

5. **REST path**
   - Introduce `/api/v1/ai/agents` with CRUD by refName (POST, GET `/{refName}`, PUT `/{refName}`, DELETE `/{refName}`), POST query, GET `/{refName}/tools`, POST `/{refName}/execute`. Optionally keep `/api/agents` as legacy and redirect or deprecate.

**Deliverables:** AgentDefinition (evolved Agent) + migration if needed; ToolResolver; GET agents/{refName}/tools; POST agents/{refName}/execute (single-shot or minimal loop); tests.

---

### Phase 3: LLM Execution Loop and Conversations

**Goal:** Implement AgentExecutionService (observe–think–act), LLMClient abstraction, and conversation persistence.

**Design decisions:**

1. **LLMClient and providers**
   - Define LLMClient (chat, chatStream), LLMRequest/LLMResponse, LLMToolDefinition, LLMToolCall. Implement Anthropic (and optionally OpenAI) client; ToolFormatConverter to map ToolDefinition → provider tool format.

2. **AgentExecutionService**
   - execute(AgentExecutionRequest): load AgentDefinition; resolve tools via ToolResolver; build messages (system prompt + conversation history + user message); call LLM with tools; on tool_calls, validate/guardrail then ToolExecutor.execute; append results; repeat until text response or max iterations/timeout. Return AgentResponse (conversationId, responseText, toolCalls, iterationsUsed, tokensUsed, status).
   - continueConversation(conversationId, userMessage): load conversation, append user message, same loop.
   - executeStreaming: similar but stream events (THINKING, TOOL_CALL_*, TEXT_DELTA, DONE).
   - Persist conversation: create/update AgentConversation and AgentConversationTurn (REFACTOR model).

3. **Conversation API**
   - GET/POST/DELETE `/api/v1/ai/conversations`, GET `/{id}`, POST `/{id}/continue`. Use AgentConversation and AgentConversationTurn repos.

4. **Guardrails (minimal)**
   - GuardrailEvaluator stub or simple checks (e.g. no_delete, require_confirmation) as in REFACTOR; plug into execution before ToolExecutor.execute.

**Deliverables:** LLMClient + Anthropic (and optionally OpenAI); AgentExecutionService; AgentConversation/AgentConversationTurn persistence and APIs; streaming and continue; tests.

---

### Phase 4: Tool Providers, MCP, and Auto-Generation

**Goal:** ToolProviderConfig, MCP import/export, and tool auto-generation from type registry.

**Design decisions:**

1. **ToolProviderConfig**
   - Entity and CRUD at `/api/v1/ai/tool-providers`; test and discover endpoints. Use for EXTERNAL_REST and EXTERNAL_MCP when implementing those routes in ToolExecutor.

2. **MCP**
   - QuantumMCPServer: listTools → ToolDefinition repo (filter by permission, availableAs); callTool → ToolExecutor.execute.
   - MCPToolImporter: connect to external MCP, discover tools, create ToolDefinitions with toolType EXTERNAL_MCP, providerRef; persist.

3. **Auto-generation**
   - Define @ToolGeneration annotation (singularName, pluralName, description, category, excludeOperations, searchableFields, tags). ToolAutoGenerator: scan @Entity + @ToolGeneration; generate ToolDefinitions (search_*, get_*, create_*, update_*, delete_*, count_*); persist with source = "auto-generated"; do not overwrite customized. POST `/api/v1/ai/tools/generate`, GET `generated`.

4. **ToolExecutor expansion**
   - Implement EXTERNAL_REST and EXTERNAL_MCP routing using ToolProviderConfig and MCP client.

**Deliverables:** ToolProviderConfig + API; QuantumMCPServer; MCPToolImporter; @ToolGeneration + ToolAutoGenerator + generate/generated API; ToolExecutor EXTERNAL_*; tests.

---

### Phase 5: Workflows and Polish (Durable Execution Deferred)

**Goal:** Workflow integration (StepRegistration, agent-as-step, workflow-as-tool) and remaining REFACTOR items. Durable execution (Restate or equivalent) is **deferred** until that infrastructure is in place.

**Design decisions:**

1. **Durable execution (deferred)**
   - executeDurable and getExecutionStatus depend on Restate (or equivalent). Skip this dependency for now; implement when Restate/job-queue is available.

2. **Workflows**
   - StepRegistration entity: name, toolRef, description, inputMapping, outputMapping. Use ToolDefinition for workflow steps. Agent-as-step: workflow step type that invokes AgentExecutionService. Workflow-as-tool: ToolDefinition with toolType FUNCTION, invocation path quantum.workflows.execute.

3. **API and config**
   - Feature flags and application properties as in REFACTOR. OpenAPI for new endpoints. Documentation (user-guide, reference) updated.

**Deliverables:** StepRegistration and workflow integration; docs and config; tests. (executeDurable + status when Restate is available.)

---

## 5. Summary Table: Gaps and Phase That Closes Them

| Gap | Phase |
|-----|--------|
| ToolDefinition entity + repo | 1 |
| ToolExecutor (routing by toolType) | 1 |
| Tool REST: CRUD, query, execute by refName, validate | 1 |
| AgentDefinition alignment (toolRefs, categories, tags, execution/memory/guardrails, securityUri, principalRef, delegateAgentRefs) | 2 |
| ToolResolver | 2 |
| GET agents/{refName}/tools, POST agents/{refName}/execute | 2 |
| LLMClient + ToolFormatConverter | 3 |
| AgentExecutionService (loop, streaming, continue) | 3 |
| AgentConversation / AgentConversationTurn + APIs | 3 |
| GuardrailEvaluator (minimal) | 3 |
| ToolProviderConfig + API | 4 |
| QuantumMCPServer, MCPToolImporter | 4 |
| @ToolGeneration, ToolAutoGenerator, generate/generated | 4 |
| ToolExecutor EXTERNAL_REST / EXTERNAL_MCP | 4 |
| executeDurable, getExecutionStatus | 5 (deferred until Restate in place) |
| StepRegistration, agent-as-step, workflow-as-tool | 5 |
| REST path migration to /api/v1/ai/* | 1–2 (optional; can be done incrementally) |

---

## 6. Recommendations

1. **Keep existing behavior:** Preserve GET `/api/agent/tools` and POST `/api/agent/execute` until ToolDefinition and ToolExecutor are in place; then make them delegate to the registry and ToolExecutor so the six gateway tools remain available with tenant and runAs behavior unchanged.

2. **Reuse:** AgentExecuteHandler’s realm resolution, runAs, and gateway dispatch logic should be reused inside ToolExecutor for QUANTUM_QUERY/QUANTUM_API. SchemaService and SchemaService.getSchemaForRootType remain as-is for schema and validation.

3. **Entity base:** Align ToolDefinition and AgentDefinition with the framework’s persistent entity pattern (realm, refName, audit fields). Use UnversionedBaseModel or the same base used by other realm-scoped entities if that is the project standard.

4. **Documentation:** Update `quantum-docs` (e.g. ai-agent-integration.adoc) after each phase to describe new APIs, tool resolution, and execution model.

5. **Tests:** Per project rules, add integration tests for each phase (tool CRUD and execute, tool resolution, agent execute, conversation persist, MCP, auto-generation).

---

## 7. Phase 1 Implementation Notes (Done)

- **ToolDefinition** and supporting types live in `quantum-models` under `com.e2eq.framework.model.persistent.tools` (ToolType, ParameterDefinition, InvocationConfig, ErrorSemantics, ToolExample, ToolDefinition).
- **ToolDefinitionRepo** is in `quantum-morphia-repos`; realm-scoped storage same pattern as AgentRepo.
- **ExecutionContext**, **ToolResult**, **ToolExecutor** interface, and **DefaultToolExecutor** are in `quantum-framework` under `com.e2eq.framework.api.tools`. DefaultToolExecutor routes QUANTUM_QUERY/QUANTUM_API via `AgentExecuteHandler.invokeGatewayTool()`; other tool types return an error.
- **GatewayInvocationResult** and refactored **AgentExecuteHandler** expose `invokeGatewayTool(tool, arguments, realm)` so both REST and ToolExecutor use the same gateway dispatch.
- **GatewayToolSeeder** seeds the six gateway tools on startup (default realm) and on first list per realm; seeded tools have `source = "manual"`.
- **ToolResource** at `/api/v1/ai/tools`: GET (list), GET `/{refName}`, POST (create), PUT `/{refName}`, DELETE `/{refName}`, GET count, POST `/{refName}/execute`, POST `/{refName}/validate`. Realm from security context or query param.
- **AgentToolsProvider** delegates to the registry when realm is set (seed + list from ToolDefinitionRepo, filter by tenant enabledTools); when realm is null returns the six static tools for backward compatibility.
- **Morphia**: add `com.e2eq.framework.model.persistent.tools` to `quarkus.morphia.packages` in application properties (done in quantum-framework test resources).
- **Tests**: `ToolResourceIT` for list, get, execute, count.

---

## 8. What’s Left to Do (after Phase 1 and Phase 2)

Phase 1 and Phase 2 are **done**. Remaining work by phase:

### Phase 2 (done)
- **Agent** extended with toolRefs, toolCategories, toolTags, excludedToolRefs, maxToolsInContext, delegateAgentRefs, description, responseFormat, securityUri, principalRef, allowedRealms, requiresApproval, enabled; legacy enabledTools used when toolRefs/categories/tags unset.
- **ToolResolver** implemented: resolveToolsForAgent(Agent, realmId) — explicit refs, category/tag inclusion, exclusions, enabled filter, maxToolsInContext trim; security and delegate agents deferred to Phase 3+.
- **ToolDefinitionRepo**: findByRefNames, findByCategoryIn, findByTagsAny added.
- **GET** `/api/v1/ai/agents/{refName}` (agent by refName), **GET** `/api/v1/ai/agents/{refName}/tools` (resolved tools), **POST** `/api/v1/ai/agents/{refName}/execute` (single-shot tool execution; request body: tool/name, arguments/params). **AgentAiResource** at `/api/v1/ai/agents`. Agent CRUD remains at `/api/agents` (AgentConfigResource).

### Phase 3 (done)
- **LLMClient** interface + **StubLlmClient** (fixed message when no real LLM); **ToolFormatConverter** (ToolDefinition → ProviderToolSchema with input_schema).
- **AgentConversation** / **AgentConversationTurn** / **ToolInvocationRecord** entities; **AgentConversationRepo**, **AgentConversationTurnRepo** (realm-scoped).
- **AgentExecutionService**: execute(AgentExecutionRequest) — observe (context + history) → think (LLM) → act (tools) → loop; continueConversation(realmId, conversationId, userMessage, principalId). Persists turns; max iterations 10.
- **GuardrailEvaluator**: evaluate(Agent, ToolDefinition, params) → ALLOW / DENY / REQUIRE_CONFIRMATION; minimal: requiresApproval + delete/side-effects → REQUIRE_CONFIRMATION.
- **Conversation APIs**: GET/POST/DELETE `/api/v1/ai/conversations`, GET `/{id}`, POST `/{id}/continue`; POST `/api/v1/ai/agents/{refName}/execute` accepts **userMessage** for full loop (else single-shot tool).
- **Remaining**: real LLM (Anthropic/OpenAI), executeStreaming (SSE).

### Phase 4 (done)
- **ToolProviderConfig** entity (refName, name, providerType REST/MCP/GRPC, baseUrl, auth, defaultHeaders, timeoutMs, mcpTransport, autoDiscoverTools) + **ToolProviderConfigRepo** + **ToolProviderResource** at `/api/v1/ai/tool-providers` (GET list, GET /{refName}, POST, PUT /{refName}, DELETE /{refName}).
- **ToolExecutor**: **EXTERNAL_REST** routing via **ExternalRestInvoker** (load ToolProviderConfig by providerRef, substitute params in path/bodyTemplate, HTTP call); **EXTERNAL_MCP** returns "not yet supported".
- **QuantumMCPServer**: listTools(realm), callTool(realm, toolRef, arguments, context); **McpBridgeResource** at `/api/v1/ai/mcp` (GET tools, POST call).
- **MCPToolImporter**: importFromProvider(realm, provider, persist) — uses **McpClient** (HTTP JSON-RPC) to list tools from provider baseUrl, creates ToolDefinitions (EXTERNAL_MCP, providerRef); **POST** `/api/v1/ai/tool-providers/{refName}/import` (body: persist).
- **McpClient** (interface) + **McpHttpClient**: listTools(baseUrl, authHeaders), callTool(baseUrl, toolName, arguments, authHeaders); JSON-RPC tools/list and tools/call over POST.
- **ToolExecutor**: **EXTERNAL_MCP** routing — load provider, McpClient.callTool; **get_/create_/update_/delete_/count_** CRUD tools map to query_find, query_save, query_delete.
- **ToolAutoGenerator**: generateForRootTypes (search_ only) and **generateFullCrudForRootTypes**(realm, rootTypes, persist, excludeOperations) — creates search_*, get_*, create_*, update_*, delete_*, count_*; **POST** `/api/v1/ai/tools/generate` (body: rootTypes, persist, optional excludeOperations).
- **@ToolGeneration** annotation (quantum-models): singularName, pluralName, description, category, excludeOperations, tags, searchableFields; for use on entity types when scanning or passing root type lists.
- **Phase 4 complete.** No remaining Phase 4 items.

### Phase 5: Workflows and polish (durable execution deferred)
- **Deferred until Restate (or equivalent) is in place:** executeDurable, getExecutionStatus. No Restate dependency for now.
- **Current scope:** StepRegistration, agent-as-workflow-step, workflow-as-tool; feature flags and application properties; update **quantum-docs** (ai-agent-integration.adoc, configuration).

### Optional / incremental
- **REST path**: migrate or alias `/api/agent` and `/api/agents` to `/api/v1/ai/*` where desired.
- **Permission** integration: tool/agent securityUri and principalRef used in permission checks.

---

## 10. Current Capabilities and What's Left

### 10.1 Capabilities We Now Have (Phases 1–4)

| Area | Capability |
|------|------------|
| **Tools** | ToolDefinition registry (realm-scoped); CRUD at `/api/v1/ai/tools`; list, get `/{refName}`, create, update, delete, count; POST `/{refName}/execute`, POST `/{refName}/validate`; gateway tools seeded; ToolExecutor routes QUANTUM_QUERY, QUANTUM_API, EXTERNAL_REST, EXTERNAL_MCP (get/create/update/delete/count_* map to gateway). |
| **Tool providers** | ToolProviderConfig CRUD at `/api/v1/ai/tool-providers`; EXTERNAL_REST invocation via config (baseUrl, path/bodyTemplate, auth). |
| **Agents** | Agent entity (toolRefs, categories, tags, exclusions, maxToolsInContext, delegateAgentRefs, securityUri, etc.); CRUD at `/api/agents`; GET `/api/v1/ai/agents/{refName}`, GET `/{refName}/tools`, POST `/{refName}/execute` (userMessage = full LLM loop, else single-shot tool). |
| **Tool resolution** | ToolResolver resolves tools for an agent (refs + categories + tags − exclusions; maxToolsInContext). |
| **Execution** | AgentExecutionService: execute (observe–think–act loop), continueConversation; StubLlmClient (real LLM pluggable); GuardrailEvaluator (minimal). |
| **Conversations** | AgentConversation / AgentConversationTurn persistence; GET/POST/DELETE `/api/v1/ai/conversations`, GET `/{id}`, POST `/{id}/continue`. |
| **MCP** | QuantumMCPServer (listTools, callTool); McpBridgeResource at `/api/v1/ai/mcp` (GET tools, POST call). |
| **Auto-generation** | POST `/api/v1/ai/tools/generate` (body: rootTypes, persist); generates `search_{type}` tools; DefaultToolExecutor maps search_X → query_find. |

### 10.2 What's Left

- **Phase 5 (no Restate):** StepRegistration, agent-as-workflow-step, workflow-as-tool; feature flags/config; quantum-docs update.
- **Deferred:** executeDurable, getExecutionStatus (when Restate or equivalent exists).
- **Phase 4:** Complete (MCP client, EXTERNAL_MCP, @ToolGeneration and full CRUD generation implemented).
- **Optional:** REST path migration to `/api/v1/ai/*`; tool/agent securityUri and principalRef in permission checks.

**PSA UI integration:** To test the new capabilities in the PSA application, see **psa-app** `doc/design/psa-ai-ui-integration-design.md` — screen inventory, backend checks, and frontend (PSM) implementation order.

---

## 11. References

- `docs/design/AI_AGENT_TOOL_REFACTOR_CLARIFICATION.md` — Target vision
- `docs/design/AI_AGENT_INTEGRATION_DESIGN.md` — Original integration design (six tools, execute, tenant config)
- `quantum-docs/src/docs/asciidoc/user-guide/ai-agent-integration.adoc` — User-facing docs
- `quantum-framework/src/main/java/com/e2eq/framework/api/agent/*` — Current agent API and handlers
- `quantum-framework/src/main/java/com/e2eq/framework/rest/resources/AgentConfigResource.java` — Current agent CRUD
- `quantum-models/src/main/java/com/e2eq/framework/model/persistent/agent/Agent.java` — Current Agent entity

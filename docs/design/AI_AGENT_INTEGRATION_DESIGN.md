# AI Agent Integration Design Document

## 1. Overview

This design enables AI agents (Cursor, Claude, ChatGPT, Gemini) and MCP clients to perform CRUDL (Create, Read, Update, Delete, List) over Quantum-backed data by integrating with the **Query Gateway** as the single integration point. No integration across domain-specific REST resources is required.

### 1.1 Goals

- **Single integration point:** Agents and MCP use only the Query Gateway (`/api/query`) for CRUDL; one set of six operations for all entity types.
- **Multi-tenant:** Each tenant (realm) has its own agent configuration and tool instances; tools list and execute are scoped by tenant.
- **Tenant-specific runAs:** Tenant configuration can specify a `runAsUserId`; when set, tool execution runs under that user’s security context (PrincipalContext), integrating with the existing security and policy framework.
- **Discovery:** Agents can discover available tools and schema (rootTypes, per-type fields) per tenant without hard-coding URLs.
- **Governance:** All agent traffic uses the same security (realm, permissions, `@FunctionalAction`) as the gateway; execute optionally runs as the tenant’s configured runAs user.
- **Optional unified execute:** One HTTP endpoint that dispatches tool-by-name to the gateway, applying tenant config (runAs, enabled tools).
- **Session/trace:** Request headers for audit and multi-turn correlation.
- **MCP bridge:** A standalone MCP server that calls only the gateway (and optional agent layer); can pass realm so backend applies tenant config.

### 1.2 Non-Goals (Out of Scope for This Design)

- Domain REST resources (e.g. `/orders`, `/products`) are not exposed as agent tools; they remain for UIs and workflows.
- Native MCP protocol inside the Quarkus app (optional later phase).
- LLM hosting or prompt management; only APIs and bridge for existing LLM clients.

---

## 2. Architecture

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│  AI Clients (Cursor, Claude Desktop, ChatGPT, Gemini)                    │
│  MCP client ↔ MCP Bridge (stdio or SSE)                                  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ MCP tools/call, resources/read
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  MCP Bridge (optional, separate process)                                 │
│  - tools: query_rootTypes, query_plan, query_find, query_save,          │
│           query_delete, query_deleteMany                                  │
│  - resources: quantum://schema, quantum://schema/{rootType}               │
│  - HTTP client → base URL + auth (env)                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ REST: /api/query/* and /api/agent/*
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Quantum Backend (Quarkus)                                                │
├─────────────────────────────────────────────────────────────────────────┤
│  AgentResource (new)           QueryGatewayResource (existing)            │
│  GET  /api/agent/tools    ──►  (permission check only; tools list)        │
│  GET  /api/agent/schema   ──►  GET /api/query/rootTypes (wrap or proxy)   │
│  GET  /api/agent/schema/      (derive from Morphia EntityModel)           │
│        {rootType}                                                         │
│  POST /api/agent/execute  ──►  dispatch to one of:                        │
│                                 POST /api/query/plan                      │
│                                 POST /api/query/find                      │
│                                 POST /api/query/save                      │
│                                 POST /api/query/delete                    │
│                                 POST /api/query/deleteMany                │
│                                 GET  /api/query/rootTypes                 │
│  (all agent endpoints use same SecurityFilter, realm, @FunctionalAction)  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Query Gateway (existing) + MorphiaDataStoreWrapper                      │
│  Single CRUDL surface for all UnversionedBaseModel types                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Single Integration Point

- **CRUDL** is fully covered by: `GET /api/query/rootTypes`, `POST /api/query/plan`, `POST /api/query/find`, `POST /api/query/save`, `POST /api/query/delete`, `POST /api/query/deleteMany`.
- **Agent APIs** are a thin layer: discovery (tools list), schema (rootTypes + per-type), and optional execute (single POST that delegates to the gateway). No new business logic; only routing, schema derivation, and tenant config application.
- **MCP bridge** talks only to `/api/query/*` and `/api/agent/*`; it does not call domain resources.

### 2.3 Multi-Tenancy and Tenant-Specific Configuration

- **Tenant = realm.** All agent APIs and tool execution are scoped by tenant (realm). The effective realm is resolved from: request (e.g. `arguments.realm`, query param `realm`, or header `X-Realm`), then principal’s default realm, then configured default.
- **Per-tenant agent configuration.** Each tenant can have its own agent configuration. This includes:
  - **runAsUserId (optional):** The userId to use as the security context when executing agent tools for that tenant. When set, the backend resolves that user’s identity (e.g. via `CredentialRepo` / identity store), builds a `PrincipalContext` for that user in the tenant’s realm, and runs the gateway under that context. Permissions and data scoping then apply as that user (same pattern as impersonation: `SecurityContext.setPrincipalContext(runAsContext)` for the duration of the tool call).
  - **enabledTools (optional):** List of tool names enabled for that tenant (e.g. `["query_rootTypes","query_plan","query_find","query_save"]`). If absent, all six tools are enabled. GET /api/agent/tools returns only enabled tools for the requested realm.
  - **defaultRealm (optional):** When the tenant has multiple realms, which realm to use when the request does not specify one.
  - **limits (optional):** Per-tenant limits (e.g. max find `limit`, rate limits); applied during execute.
- **Where tenant config is stored.** Design supports either:
  - **Persistent store (recommended):** New entity `AgentTenantConfig` (or `TenantAgentConfig`) stored per realm (e.g. in a collection `agentTenantConfigs` keyed by realm or tenantId). Fields: realm (or tenantId), runAsUserId, enabledTools, defaultRealm, limits, audit metadata. Applications create/update via admin API or seed.
  - **Application config:** Properties such as `quantum.agent.tenant.{realm}.runAsUserId`, `quantum.agent.tenant.{realm}.enabledTools`, etc. Suitable for small or static tenant sets.
- **Caller permission.** The caller (authenticated principal) must be allowed to use the agent API for the target tenant (e.g. permission to invoke agent tools for that realm). The actual gateway execution runs under the tenant’s runAs user (if configured) or under the caller; in both cases the framework’s permission and policy rules apply to the effective principal.
- **Audit.** When runAs is used, audit logs should record both the caller and the runAs user (e.g. actingOnBehalfOf or similar) so compliance and support can see who invoked the tool and as whom it ran.

---

## 3. API Specifications

### 3.1 Contract: Six Gateway Tools

All agent and MCP tooling is defined in terms of these six operations. Request/response bodies match the existing `QueryGatewayResource` DTOs.

| Tool name           | Gateway endpoint           | Request body (JSON)                                                                 | Response |
|---------------------|----------------------------|--------------------------------------------------------------------------------------|----------|
| `query_rootTypes`   | `GET /api/query/rootTypes` | (none)                                                                              | `RootTypesResponse` |
| `query_plan`       | `POST /api/query/plan`     | `{ "rootType": string, "query": string }`                                            | `PlanResponse` |
| `query_find`       | `POST /api/query/find`     | `{ "rootType": string, "query"?: string, "page"?: { "limit"?, "skip"? }, "sort"?: [{ "field", "dir" }], "realm"?: string }` | Find response (list + total) |
| `query_save`       | `POST /api/query/save`     | `{ "rootType": string, "entity": object, "realm"?: string }`                        | `SaveResponse` |
| `query_delete`      | `POST /api/query/delete`   | `{ "rootType": string, "id": string, "realm"?: string }`                              | `DeleteResponse` |
| `query_deleteMany` | `POST /api/query/deleteMany` | `{ "rootType": string, "query": string, "realm"?: string }`                         | `DeleteManyResponse` |

Reuse existing DTOs from `QueryGatewayResource`: `PlanRequest`, `PlanResponse`, `FindRequest`, `SaveRequest`, `SaveResponse`, `DeleteRequest`, `DeleteResponse`, `DeleteManyRequest`, `DeleteManyResponse`, `RootTypesResponse`, `RootTypeInfo`, `Page`, `SortSpec`.

---

### 3.2 GET /api/agent/tools

**Purpose:** Return a machine-readable list of gateway tools so agents/MCP can discover capabilities without hard-coding. **Multi-tenant:** List is scoped by realm; only tools enabled for that tenant are returned.

**Query params (optional):** `realm` — realm (tenant) to use. If absent, use principal’s default realm or X-Realm. Response is filtered by tenant’s `enabledTools` when tenant config is present.

**Security:** Same as Query Gateway: `@FunctionalMapping(area="integration", domain="query")` and method-level `@FunctionalAction`. Caller must be allowed to use the agent API for the requested realm. Only return tools the current principal is allowed to use (permission for integration/query and the action) **and** that are enabled for that tenant (from `AgentTenantConfig.enabledTools`; if absent, all six).

**Response: 200 OK**

```json
{
  "tools": [
    {
      "name": "query_rootTypes",
      "description": "List all available entity types (rootTypes) that can be used with find, save, delete. Returns class name, simple name, and collection name.",
      "parameters": {
        "type": "object",
        "properties": {},
        "required": []
      },
      "area": "integration",
      "domain": "query",
      "action": "listRootTypes"
    },
    {
      "name": "query_plan",
      "description": "Get the execution plan for a query (FILTER vs AGGREGATION mode and expand paths). Use before find to understand how a query will run.",
      "parameters": {
        "type": "object",
        "properties": {
          "rootType": { "type": "string", "description": "Entity type (e.g. Location, Order)" },
          "query": { "type": "string", "description": "BIAPI query string" }
        },
        "required": [ "rootType", "query" ]
      },
      "area": "integration",
      "domain": "query",
      "action": "plan"
    },
    {
      "name": "query_find",
      "description": "Find entities matching a query. Supports paging, sort, and optional realm.",
      "parameters": {
        "type": "object",
        "properties": {
          "rootType": { "type": "string", "description": "Entity type" },
          "query": { "type": "string", "description": "BIAPI query string" },
          "page": { "type": "object", "properties": { "limit": { "type": "integer" }, "skip": { "type": "integer" } } },
          "sort": { "type": "array", "items": { "type": "object", "properties": { "field": { "type": "string" }, "dir": { "type": "string", "enum": [ "ASC", "DESC" ] } } } },
          "realm": { "type": "string", "description": "Optional tenant realm" }
        },
        "required": [ "rootType" ]
      },
      "area": "integration",
      "domain": "query",
      "action": "find"
    },
    {
      "name": "query_save",
      "description": "Create or update an entity. Include entity fields as JSON; id or refName for updates.",
      "parameters": {
        "type": "object",
        "properties": {
          "rootType": { "type": "string" },
          "entity": { "type": "object", "description": "Entity data" },
          "realm": { "type": "string" }
        },
        "required": [ "rootType", "entity" ]
      },
      "area": "integration",
      "domain": "query",
      "action": "save"
    },
    {
      "name": "query_delete",
      "description": "Delete one entity by ID (ObjectId hex string).",
      "parameters": {
        "type": "object",
        "properties": {
          "rootType": { "type": "string" },
          "id": { "type": "string", "description": "ObjectId hex string" },
          "realm": { "type": "string" }
        },
        "required": [ "rootType", "id" ]
      },
      "area": "integration",
      "domain": "query",
      "action": "delete"
    },
    {
      "name": "query_deleteMany",
      "description": "Delete all entities matching a query. Use with care.",
      "parameters": {
        "type": "object",
        "properties": {
          "rootType": { "type": "string" },
          "query": { "type": "string" },
          "realm": { "type": "string" }
        },
        "required": [ "rootType", "query" ]
      },
      "area": "integration",
      "domain": "query",
      "action": "deleteMany"
    }
  ],
  "count": 6
}
```

**Implementation:** Resolve realm (query param, X-Realm, principal default). Load tenant config for that realm (AgentTenantConfig or config properties). Build the list from the six tools; filter by (1) permission (caller allowed for integration/query and each action), (2) tenant’s `enabledTools` (if present; otherwise all six). Do not scan JAX-RS resources.

---

### 3.3 GET /api/agent/schema

**Purpose:** List entity types (gateway-derived). Single source of truth: reuse or wrap `GET /api/query/rootTypes`.

**Security:** Same as gateway (integration/query); require permission for listRootTypes or equivalent.

**Response: 200 OK**

- Option A: Proxy/wrap `QueryGatewayResource.listRootTypes()` and return the same `RootTypesResponse` (rootTypes array, count).
- Option B: Add optional per-type field summary (name, type) in a second field `typeSummaries` keyed by simpleName, for LLM context. Keep payload small.

**Suggested response (Option B):**

```json
{
  "rootTypes": [ { "className": "...", "simpleName": "Location", "collectionName": "locations" }, ... ],
  "count": 12,
  "typeSummaries": {
    "Location": { "fields": [ { "name": "refName", "type": "string" }, { "name": "name", "type": "string" }, ... ] }
  }
}
```

`typeSummaries` is optional (e.g. query param `?includeFields=true`) and derived from Morphia `EntityModel` for each root type.

---

### 3.4 GET /api/agent/schema/{rootType}

**Purpose:** JSON Schema (or equivalent) for one root type so the agent can build valid find/save payloads.

**Path:** `rootType` = simple name or FQCN (same resolution as gateway `resolveRoot`).

**Security:** Same as gateway; require read/schema permission.

**Response: 200 OK**

- JSON Schema for the entity type: `type: object`, `properties` from Morphia `EntityModel` (field name, type, optional `description`). Exclude internal fields (e.g. `_id` as optional if needed for updates). Keep concise for LLM context.

**Implementation:** New helper that accepts `Class<? extends UnversionedBaseModel>` and returns a JSON object (Map or JsonObject) representing JSON Schema. Use Morphia `EntityModel` and reflection; map Java types to JSON Schema types (String, Number, Boolean, array, object, string format date/datetime). Optionally cache per class in request scope.

---

### 3.5 POST /api/agent/execute

**Purpose:** Single entry point to run one of the six gateway tools by name and arguments. **Multi-tenant:** Realm is resolved from request; tenant config (runAs, enabled tools, limits) is applied before execution.

**Request body:**

```json
{
  "tool": "query_find",
  "arguments": {
    "rootType": "Location",
    "query": "status:ACTIVE",
    "page": { "limit": 10 },
    "realm": "optional-tenant-realm"
  },
  "sessionId": "optional-uuid",
  "traceId": "optional-trace"
}
```

- **tool** (required): One of `query_rootTypes`, `query_plan`, `query_find`, `query_save`, `query_delete`, `query_deleteMany`. Must be enabled for the resolved tenant (see §2.3).
- **arguments** (required): Object matching the parameters for that tool (see §3.1). Pass through to the gateway request body as-is. `arguments.realm` (optional) contributes to realm resolution.
- **sessionId**, **traceId** (optional): Stored or logged for audit; optionally echo in response headers.

**Response:** Same as the underlying gateway operation (e.g. find returns list + total; save returns SaveResponse; delete returns DeleteResponse). Status code mirrors gateway (200, 404, 400, etc.).

**Implementation:**

1. **Realm:** Resolve from `arguments.realm`, then X-Realm, then principal’s default realm, then tenant config’s defaultRealm (if loaded), then application default.
2. **Tenant config:** Load `AgentTenantConfig` (or config) for the resolved realm. If tool is not in tenant’s `enabledTools` (when present), return 403.
3. **runAs:** If tenant config has `runAsUserId`, resolve that user’s identity (e.g. `CredentialRepo.findByUserId(runAsUserId, systemRealm)` or realm-specific credential store), build a `PrincipalContext` for that user in the tenant’s realm (same pattern as `SecurityFilter.buildImpersonatedContext` / impersonation). Temporarily set `SecurityContext.setPrincipalContext(runAsContext)` (and optionally set actingOnBehalfOf to the caller for audit). If no runAsUserId, use current principal.
4. **Execute:** Validate `tool` is one of the six; map to gateway method; call existing gateway logic (shared facade or resource internals). Apply tenant limits (e.g. cap find `limit`) if configured.
5. **Restore:** After execution, restore original `PrincipalContext` if runAs was used (e.g. in a try/finally).
6. **Security:** Caller must be allowed to use the agent API for the target realm; actual execution runs under runAs user (or caller). Same permission and policy framework applies to the effective principal.
7. **Audit:** Log caller, realm, tool, and effective principal (runAs user if set); include sessionId/traceId when present.
8. Optional: validate `arguments` against the tool’s JSON Schema and return 400 on invalid args.

---

### 3.6 Session / Trace Headers

**Purpose:** Correlate agent requests for audit and multi-turn debugging.

**Request headers (optional):**

- `X-Agent-Session-Id`: Session or conversation id.
- `X-Agent-Trace-Id` (or `X-Request-Trace-Id`): Trace id for the request.

**Implementation:** In `SecurityFilter` or a dedicated request filter: read headers, put values into a request-scoped context (e.g. `SecurityContext` extension or CDI request-scoped bean). Logging and audit can then attach these to log lines or audit records. Do not change business logic; only propagation and logging.

**Response (optional):** Echo `X-Agent-Session-Id` and `X-Agent-Trace-Id` in response headers or in a small envelope for client correlation.

---

## 4. Backend Implementation

### 4.1 Multi-Tenant Data Model: AgentTenantConfig

When using a persistent store for tenant config, use an entity (e.g. `AgentTenantConfig`) keyed by realm (or tenantId). Suggested fields:

| Field | Type | Description |
|-------|------|-------------|
| `realm` (or `tenantId`) | String | Tenant/realm identifier; unique. |
| `runAsUserId` | String (optional) | userId to use as security context when executing agent tools for this tenant. Resolved via `CredentialRepo` / identity store; gateway runs under that user’s PrincipalContext. |
| `enabledTools` | List&lt;String&gt; (optional) | Tool names enabled for this tenant (e.g. `query_rootTypes`, `query_find`, …). If null or empty, treat as “all six enabled”. |
| `defaultRealm` | String (optional) | Default realm when request does not specify one (e.g. when tenant has multiple realms). |
| `limits` | Map or embedded (optional) | e.g. `maxFindLimit`, `rateLimitPerMinute`; applied during execute. |
| Audit / metadata | as needed | created/updated timestamps, who created, etc. |

**Storage:** Collection name e.g. `agentTenantConfigs`; realm (or tenantId) as unique key. Can live in system realm or a dedicated config store. Applications create/update via admin API or seed; caller must have admin permission for that tenant.

**Alternative:** Config properties `quantum.agent.tenant.{realm}.runAsUserId`, `quantum.agent.tenant.{realm}.enabledTools` (comma-separated), etc., for small/static tenant sets.

### 4.2 Module and Package

- **Module:** `quantum-framework` (agent endpoints are part of the same REST surface as the gateway). Optional: `quantum-morphia-repos` or app module for `AgentTenantConfig` entity and repo if using persistent store.
- **Package:** `com.e2eq.framework.api.agent` (e.g. `AgentResource`, `AgentToolsProvider`, `AgentExecuteHandler`, `SchemaService`, `TenantAgentConfigResolver`).

### 4.3 New Classes

| Class | Responsibility |
|-------|----------------|
| `AgentResource` | JAX-RS resource: `GET /api/agent/tools`, `GET /api/agent/schema`, `GET /api/agent/schema/{rootType}`, `POST /api/agent/execute`. Resolves realm; delegates to tools provider, schema service, execute handler; applies tenant config. |
| `AgentToolsProvider` | Builds the list of six tools (name, description, parameters JSON Schema). Filters by permission and by tenant’s `enabledTools` (from TenantAgentConfigResolver). |
| `TenantAgentConfigResolver` | Loads tenant config for a given realm: from `AgentTenantConfig` repo (or config properties). Returns runAsUserId, enabledTools, defaultRealm, limits. |
| `AgentExecuteHandler` | Resolves realm; loads tenant config; if runAsUserId set, resolves that user’s PrincipalContext (CredentialRepo + build context like impersonation), runs gateway under that context (SecurityContext.setPrincipalContext), then restores; dispatches tool to gateway; applies tenant limits. |
| `SchemaService` | Gateway-derived schema: `listRootTypes(realm)` wrapper, `getSchemaForRootType(String rootType)` from Morphia `EntityModel`. |
| `AgentTenantConfig` (optional) | Entity for per-tenant agent config when using persistent store. |
| `AgentTenantConfigRepo` (optional) | Repo for AgentTenantConfig (findByRealm, save, etc.). |

### 4.4 AgentResource Endpoints Summary

| Method | Path | Delegation |
|--------|------|------------|
| GET | /api/agent/tools | `AgentToolsProvider.getTools()` (permission-filtered) |
| GET | /api/agent/schema | `SchemaService.listSchema()` → wraps rootTypes + optional typeSummaries |
| GET | /api/agent/schema/{rootType} | `SchemaService.getSchemaForRootType(rootType)` |
| POST | /api/agent/execute | `AgentExecuteHandler.execute(tool, arguments)` → gateway |

### 4.5 Security and Realm

- **@FunctionalMapping:** `area="integration", domain="query"` (same as QueryGatewayResource).
- **@FunctionalAction** per method: e.g. `listTools`, `listSchema`, `getSchema`, `execute`. Reuse or align action names with gateway (listRootTypes, plan, find, save, delete, deleteMany) for permission rules.
- Realm resolution for execute: use `arguments.realm`, then X-Realm, then principal default, then tenant config’s defaultRealm (if any), then application default.
- **runAs:** When tenant config has runAsUserId, execute runs under that user’s PrincipalContext (same security/policy framework); caller is still required to be allowed to use the agent API for that realm. Audit records both caller and runAs user.
- No new bypass of data scoping; execute runs through the same security and realm as the gateway (under the effective principal: runAs or caller).

### 4.6 SchemaService: Deriving Schema from Morphia

- **listSchema:** Call `MorphiaDataStoreWrapper.getDataStore(defaultRealm).getMapper().getMappedEntities()`, filter to `UnversionedBaseModel` subclasses, build list of RootTypeInfo (reuse or mirror gateway). Optionally for each type get `EntityModel` and build a short list of field names/types for `typeSummaries`.
- **getSchemaForRootType(String):** Resolve `rootType` to `Class<? extends UnversionedBaseModel>` (same logic as gateway `resolveRoot`). Get `EntityModel` for that class; iterate properties, map Java type to JSON Schema type; return a Map or JsonObject suitable for JSON response. Exclude or mark read-only internal fields as needed.

### 4.7 Configuration / Feature Flags

- `feature.agent.tools.enabled` (default `true`): If false, return 503 or empty list for `/api/agent/tools`.
- `feature.agent.execute.enabled` (default `true`): If false, `POST /api/agent/execute` returns 503.
- Optional: `feature.agent.schema.includeFields` (default `true`) for including typeSummaries in list schema.

---

## 5. MCP Bridge (Standalone)

### 5.1 Role

The MCP bridge is a separate process (TypeScript or Python) that implements an MCP server. It exposes exactly six tools and optionally gateway-derived resources. It calls only the Quantum backend’s `/api/query/*` and `/api/agent/*` endpoints.

### 5.2 Tools Implementation

- **tools/list:** Call `GET /api/agent/tools` and map response to MCP tool list format (name, description, inputSchema). If agent/tools is not available, return a static list of the six tools with the same parameter schemas as in §3.2.
- **tools/call:** On tool name and arguments, either call `POST /api/agent/execute` with `{ "tool": name, "arguments": args }` or call the corresponding gateway endpoint directly (e.g. `POST /api/query/find` with `args` as body). Prefer execute when available so one code path is used.

### 5.3 Resources Implementation (Optional)

- **resources/list** or **resources/templates/list:** Expose template `quantum://schema` and `quantum://schema/{rootType}`.
- **resources/read:** For URI `quantum://schema`, GET `/api/agent/schema` or `GET /api/query/rootTypes` and return as text or JSON content. For `quantum://schema/{rootType}`, GET `/api/agent/schema/{rootType}` and return. MIME type `application/json`.

### 5.4 Configuration (Environment)

- `QUANTUM_BASE_URL` (required): Base URL of the Quantum backend (e.g. `https://api.example.com`).
- `QUANTUM_AUTH_TOKEN` (required): Bearer token or API key; bridge sends `Authorization: Bearer <token>` (or appropriate header) on every request.
- Optional: `QUANTUM_REALM` — default realm (tenant) for requests that do not specify realm. Backend will apply that tenant’s agent config (runAs, enabled tools).
- **Multi-tenant:** When the backend uses tenant config, the bridge can pass realm per request: include `realm` in tool arguments (e.g. `arguments.realm`) or send `X-Realm` header so the backend resolves the tenant and applies runAs / enabled tools for that tenant.

### 5.5 Where to Implement

- **Option A:** New repo or repo subtree `quantum-mcp-bridge` (e.g. Node/TypeScript with `@modelcontextprotocol/sdk`).
- **Option B:** Module under framework repo, e.g. `quantum-mcp-bridge/` with its own `package.json` or `pyproject.toml`, documented in framework docs.

---

## 6. Implementation Phases

### Phase 1: Agent REST API (Backend)

1. Add package `com.e2eq.framework.api.agent`.
2. **Tenant config (optional for Phase 1):** Define `AgentTenantConfig` entity and repo (or config property resolver) with realm, runAsUserId, enabledTools, defaultRealm, limits. Implement `TenantAgentConfigResolver` to load config by realm (from repo or config).
3. Implement `AgentToolsProvider`: fixed list of six tools with name, description, parameters (JSON Schema); filter by permission (integration/query and actions) and by tenant’s `enabledTools` when tenant config is present. Accept realm (from query or context).
4. Implement `SchemaService`: wrap `rootTypes` from gateway; add `getSchemaForRootType(rootType)` using Morphia EntityModel → JSON Schema. Accept optional realm for mapper/datastore.
5. Implement `AgentResource`: GET /api/agent/tools?realm=… (optional), GET /api/agent/schema, GET /api/agent/schema/{rootType}. Resolve realm; delegate to tools provider (with realm) and schema service. Apply `@FunctionalMapping` and `@FunctionalAction`.
6. Add integration tests: call agent endpoints with test user; verify tools list and schema response shape; verify permission filtering; optionally verify tenant-enabled tools filtering when tenant config is present.
7. Document in user guide (already in ai-agent-integration.adoc); add link from reference guide.

### Phase 2: Execute and Headers (with Multi-Tenant runAs)

1. Implement `AgentExecuteHandler`: (1) Resolve realm from arguments, X-Realm, principal, tenant config default, app default. (2) Load tenant config via `TenantAgentConfigResolver`. (3) If tenant has runAsUserId, resolve that user’s credential (e.g. `CredentialRepo.findByUserId(runAsUserId, systemRealm)`), build PrincipalContext for that user in the tenant’s realm (reuse pattern from SecurityFilter impersonation / buildImpersonatedContext). (4) Temporarily set `SecurityContext.setPrincipalContext(runAsContext)`; set actingOnBehalfOf to caller for audit. (5) Dispatch by `tool` to gateway (plan, find, save, delete, deleteMany, rootTypes); call existing gateway logic. (6) Apply tenant limits (e.g. cap find limit) if configured. (7) Restore original PrincipalContext in finally. If no runAsUserId, run under current principal.
2. Add POST /api/agent/execute to `AgentResource`; validate tool name and that tool is enabled for the tenant; optionally validate arguments.
3. Add request filter or SecurityFilter extension: read `X-Agent-Session-Id`, `X-Agent-Trace-Id`; put into request-scoped context; ensure logging can include them.
4. Integration tests: execute each of the six tools via POST /api/agent/execute; verify response matches gateway; verify 400 for unknown tool; verify 403 when tool not enabled for tenant; verify runAs (execute runs under configured user when tenant config has runAsUserId).
5. Optional: echo session/trace in response headers. Audit log: caller, realm, tool, effective principal (runAs user if set).

### Phase 3: MCP Bridge

1. Create bridge project (TypeScript or Python); add MCP SDK dependency.
2. Implement tools/list (from GET /api/agent/tools or static list) and tools/call (POST /api/agent/execute or direct gateway calls).
3. Implement optional resources: quantum://schema, quantum://schema/{rootType}.
4. Document env vars and example Cursor/Claude config (e.g. in ai-agent-integration.adoc or README in bridge repo).
5. Manual test: run bridge with stdio; connect Cursor or Claude Desktop; run a few tool calls (rootTypes, find, save).

### Phase 4: Polish and Optional

1. Feature flags: `feature.agent.tools.enabled`, `feature.agent.execute.enabled`.
2. OpenAPI/annotations for new endpoints if project uses them.
3. Optional: retrieval context endpoint `POST /api/agent/context` (gateway find by id or query, limit size) and optional ontology summary.

---

## 7. Testing Strategy

- **Unit:** AgentToolsProvider (list of six, optional permission filter). SchemaService (rootType resolution, EntityModel → JSON Schema for a known type).
- **Integration:** AgentResource: GET tools (auth, shape), GET schema (rootTypes + optional typeSummaries), GET schema/{rootType} (valid and invalid rootType). POST execute: each tool once with valid args; 400 for unknown tool; 401/403 when unauthorized. Session/trace headers logged or available in context.
- **Existing gateway tests:** Unchanged; agent layer only delegates. No new gateway tests required unless refactoring to extract a shared facade for execute.

---

## 8. References

- User guide: `quantum-docs/src/docs/asciidoc/user-guide/ai-agent-integration.adoc`
- Query Gateway: `QueryGatewayResource`, `quantum-docs/.../planner-and-query-gateway.adoc`
- REST CRUD: `quantum-docs/.../rest-crud.adoc`
- MCP: https://modelcontextprotocol.io (spec, server concepts, build server, SDKs)

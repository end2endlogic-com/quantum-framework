# Agent vs Tool: LLM + Context (Design Note)

## Two Possible Terminologies

### Option A: "Tool" = LLM + context (earlier in this doc)

- **Tool** = the configured LLM with context (prompts); the thing that *answers* questions.
- **Agent actions** (renamed from current "tools") = what that LLM *invokes* (query_find, query_save, …).

### Option B: "Agent" = LLM + context, "Tools" = what the agent uses (your framing)

- **Agent** = the LLM configured via a **context** (sequential prompts); the thing that "thinks" and answers questions.
- **Tools** = specialized tasks/capabilities that an **agent** can leverage (query_find, query_save, obligations_suggest, …). The agent *uses* tools to do work.

Option B matches common industry usage (MCP, LangChain, etc.): the **agent** is the LLM/orchestrator; **tools** are what the agent can call. It also matches our existing API path: we already have `/api/agent` (tools, execute, schema). So "agent" is the umbrella; we're only missing the **Agent** as a first-class configurable entity (LLM + context). No renaming of "tools" is needed.

**Recommendation: use Option B.** Reserve **Agent** for "LLM + context"; keep **Tools** as the callable capabilities the agent (or an external LLM) uses. Add an Agent entity, API, and UI; leave the current "tools" as-is in name and behavior.

---

## Your Understanding (Confirmed)

What you want to create is:

- An **LLM** configured via a **context**: a collection of **sequential prompts** that set the context for the kinds of questions this thing is expected to answer.
- A screen to create it: (1) **reference an LLM configuration**, and (2) **create the context** (ordered prompts).

If we call that thing an **Agent** (Option B), then **tools** are the specialized tasks that agent (or any LLM) can leverage — query_find, query_save, etc. — which is exactly what we have today. So we add the Agent concept; we don't rename the existing tools.

---

## Current State: Two Different "Tool" Concepts

### 1. Framework "agent tool" (what exists today)

- **Meaning**: A **callable capability** the LLM can invoke (e.g. `query_find`, `query_save`, `obligations_suggest`).
- **Where**: `AgentToolsProvider`, `GET /api/agent/tools`, `POST /api/agent/execute`.
- **Content**: Name, description, parameters (JSON Schema-like). No LLM, no prompts, no "context."
- **Tenant**: `enabledTools` in `TenantAgentConfig` only controls which of these capabilities are visible per realm.

So today, "tool" = *something the LLM can call*, not *an LLM configured with a context*.

### 2. Desired "agent" (LLM + context) — Option B

- **Meaning**: An **Agent** = a **configured LLM** bound to a **context** (ordered prompts) that defines what kinds of questions this agent answers. **Tools** (query_find, query_save, …) are what the agent (or an external LLM) can *use* to do work.
- **Not present**: No first-class **Agent** entity, no API, and no UI for creating agents (LLM + context). The *tools* (callable ops) already exist.

---

## What We Have Today (Relevant Pieces)

| Layer        | What exists |
|-------------|-------------|
| **Framework** | Agent "tools" = callable operations (query_find, etc.); tenant `enabledTools`; no LLM, no prompts, no context. |
| **psa-app**   | **One** per-realm LLM config: `LlmProviderSettings` (provider type + API key). No "tools" that reference it; no context (prompts). |
| **UI**        | Secrets/settings screen for that single LLM config (provider + API key). No tool CRUD, no context (prompts) editor. |

So: we have **LLM configuration** (one per realm) and **tools** (callable ops under `/api/agent/tools`), but we do **not** have:

- An **Agent** entity: name + reference to LLM config + **context** (ordered prompts).
- An API to CRUD agents (and their context).
- A UI to create agents, reference an LLM config, and create/edit the context.

---

## What Would Be Needed (Option B: Agent = LLM + context)

### 1. Model (framework or app)

- **Agent** (first-class entity):
  - `name`, `refName` (or id)
  - **LLM config reference**: e.g. ref to realm’s default LLM config, or to a named LLM config (if we later support multiple configs per realm).
  - **Context**: ordered list of **prompts** (e.g. `List<PromptStep>` with `order`, `role` (system/user), `content` or template).
- **Context** = list of prompt steps (system prompt 1, system prompt 2, …) sent in order to set the LLM’s context before user questions.
- Optionally: **enabled tools** per agent — which of the existing tools (query_find, query_save, …) this agent is allowed to use.

### 2. API

- CRUD for **agents** (create, read, update, delete).
- Optionally: "invoke agent" endpoint that takes agent id + user message, resolves LLM config, applies context (sequential prompts), then calls the LLM (and the LLM may call tools via existing execute flow). Returns the reply.
- Existing **tools** (GET /api/agent/tools, POST /api/agent/execute) stay as-is; they are what the agent (or external LLM) uses.

### 3. UI

- **Screen: Create / Edit Agent**
  - Name (and maybe refName).
  - **Reference LLM configuration**: e.g. dropdown of available LLM configs (today: single per realm; later: multiple).
  - **Context**: create and order prompts (e.g. add prompt, set role + content, reorder). This is the "collection of sequential prompts that set the context for the kinds of questions the agent is expected to answer."
  - Optionally: which **tools** this agent can use (subset of query_find, query_save, …).

---

## Where It Could Live

- **Framework**: If we want multiple apps to define **agents** (LLM + context) and reuse discovery/execute patterns, a minimal **Agent** model and API could live in the framework. The framework could define the shape (name, LLM config ref, context) and leave LLM config resolution to the app or a small SPI.
- **psa-app**: If this is only for PSA (e.g. "obligation suggest" agent, future PSA-specific agents), the **Agent** entity and API could live in psa-app and reference the existing `LlmProviderSettings` (or a future multi-config) and add **context** (ordered prompts) and UI as above.

---

## What Is the Point of the Existing "Tool" Concept?

The existing **tools** (query_find, query_save, etc.) exist so that **an agent** (or an external LLM) can **do work**:

- An **agent** (LLM + context) or an **external** client (Cursor, MCP) calls `GET /api/agent/tools` to discover **what capabilities it can invoke** (find data, save entity, etc.).
- The client then calls `POST /api/agent/execute` with a tool name (e.g. `query_find`) and arguments; the backend runs the operation.
- So **tools** = **specialized tasks the agent (or LLM) leverages**. They are the right name and the right concept; we just didn't have the **Agent** (LLM + context) as a first-class thing that *uses* them.

---

## Recommended Terminology (Option B)

| Concept | Role |
|--------|------|
| **Agent** | LLM + context (the thing that "thinks" and answers questions). **New** first-class entity; we add CRUD, UI, optional "invoke agent" endpoint. |
| **Tools** | Specialized tasks the agent (or external LLM) leverages — query_find, query_save, obligations_suggest, etc. **Existing**; no rename. |

- **Agent** = configurable (name, LLM config ref, context = ordered prompts). UI: create/edit agents.
- **Tools** = what the agent uses. `GET /api/agent/tools` and `POST /api/agent/execute` stay as-is; tenant `enabledTools` continues to filter which tools are available per realm.
- Optionally: per-**Agent** "enabled tools" so each agent can be restricted to a subset of tools it may call.

No renaming of the current "tools" is required. We only **add** the Agent concept (LLM + context).

---

## Scenarios (Agent + Tools in Practice)

### Scenario 1: Obligations Agent (Suggest → Review → Persist)

**Agent**: An **Obligations Agent** prompt-engineered to review current tax (or regulatory) law for the **jurisdictions** associated with a **given entity** and suggest what **trackable obligations** should be created.

**Context (prompts)**: Sequential prompts that set the agent’s role, e.g. “You are an obligations analyst. Given an entity and its jurisdictions, review applicable requirements and suggest a list of trackable obligations (details, due date, priority, source). Output structured suggestions; do not persist until the user confirms.”

**Flow**:

1. **User context**: User is on a Jurisdiction or Legal Entity detail page; they have (or select) an entity and one or more jurisdictions.
2. **Invoke agent**: User triggers “Suggest obligations.” The system invokes the **Obligations Agent** (LLM + context) with entity id, jurisdiction id(s), and optional entity summary. The agent uses its context and the LLM to produce a **list of suggested obligations** (details, due date, priority, source). No persistence yet.
3. **Review**: Frontend displays the suggestions; user can deselect some. Optional: show source (e.g. requirement ref, regulation name).
4. **User continues**: User clicks “Accept selected” (or “Create these obligations”). The agent then **uses a tool** to persist:
   - **Tool**: e.g. `query_save` (or a domain `POST /psa/obligations` / batch save). The agent (or the backend on behalf of the user) maps each selected suggestion to the **Obligation** ontological object (schema), builds the JSON (details, dueDate, deadline, priority, status, references to Jurisdiction/Requirement/Entity), and calls the save API for each obligation.
5. **Outcome**: Obligation records are created and linked to the entity/jurisdiction/requirement as appropriate; user sees confirmation.

**Summary**: The **agent** does the “thinking” (review tax/regulatory context, suggest obligations). The **tool** does the “doing” (map suggestions → Obligation entity JSON → call save API). Humans stay in the loop (review/accept) before persistence.

#### One agent vs two agents (suggest vs persist)

**Option A: One Obligations Agent**  
One agent both suggests and (after user accepts) knows how to map suggestions to Obligation JSON and call the save tool. Simpler UX (one agent), but that agent needs two kinds of context: (1) tax/regulatory reasoning for suggestions, (2) Obligation schema and save API usage for persistence. Broader scope and tool access (read + write).

**Option B: Two agents (recommended)**  
- **Suggestions Agent (Obligations Suggest)**: Prompt-engineered to review tax/regulatory context for the entity's jurisdictions and produce a **list of suggested obligations** (details, due date, priority, source). It does **not** call write tools; it only outputs structured suggestions. Context = domain reasoning; enabled tools = read-only (e.g. query_find for requirements/jurisdictions if needed) or none.
- **Persistence Agent (Create/Persist via API)**: Prompt-engineered to **map approved suggestion DTOs to the Obligation ontological object** and **call the save API** (e.g. `query_save` or `POST /psa/obligations`). Context = schema knowledge + "given these suggestions, build valid Obligation JSON and call save for each." Enabled tools = only the save tool(s). It does **not** suggest; it only persists what it is given.

**Why two agents helps**

- **Separation of concerns**: Suggest = reasoning; Persist = schema + API. Each agent has a narrow, clear job.
- **Safety**: The persistence agent has a narrow mandate ("persist only what you are given"). Reduces risk of the LLM creating data the user did not approve.
- **Reusability**: The persistence agent pattern ("map structured input to entity JSON, call save") can be reused for other flows (e.g. persist suggested controls, suggested tasks) with a different schema and tool.
- **Permissions**: You can restrict the suggestions agent to read-only tools and the persistence agent to only the save tool(s). Easier to reason about and audit.

**Handoff**

1. User triggers "Suggest obligations." System invokes the **Suggestions Agent** with entity id, jurisdiction id(s). Agent returns a list of suggestion DTOs.
2. User reviews and deselects some; clicks "Accept selected."
3. System passes the **selected suggestions** (and context: jurisdiction id, entity id, realm) to the **Persistence Agent**. That agent maps each suggestion to Obligation JSON (using schema) and calls the save tool for each. Returns success/failure per obligation.
4. User sees confirmation (e.g. "7 created, 2 failed.").

The handoff is **orchestrated by the backend or frontend**: user action "Accept selected" triggers invocation of the Persistence Agent with the selected list as input. No need for the Suggestions Agent to "call" the Persistence Agent; the system does the handoff.

---

### Scenario 2: Query Assistant Agent (Answer questions using data)

**Agent**: A **Query Assistant** prompt-engineered to answer questions about the data in the realm (e.g. “How many active locations in the West region?”, “List orders for customer X”).

**Context (prompts)**: e.g. “You have access to tools that can query the database. Use query_plan to understand how a query will run, query_find to retrieve entities. Use the schema (root types, properties) to build valid queries. Answer the user’s question concisely.”

**Flow**:

1. User asks a natural-language question.
2. The agent (LLM + context) decides which **tools** to call: e.g. `query_find` with `rootType: "Location"`, `query: "region:West && status:ACTIVE"`, `page: { limit: 100 }`. It may call `query_plan` first, then `query_find`.
3. The backend executes the tool(s) via `POST /api/agent/execute`; results are returned to the agent.
4. The agent synthesizes the results and answers the user (e.g. “There are 12 active locations in the West region.”).

**Summary**: The **agent** interprets the question and chooses tools; the **tools** (query_find, query_plan) perform the data operations. No persistence in this scenario; the agent only reads.

---

### Scenario 3 (future): Compliance Reviewer Agent

**Agent**: Prompt-engineered to review an entity’s obligations and controls and suggest gaps or next actions.

**Flow**: Agent uses **tools** such as `query_find` (obligations, controls, evidence) and optionally a domain “evaluate control” or “suggest actions” tool; it returns a narrative and/or structured suggestions. If the user approves, it could use `query_save` (or domain APIs) to create follow-up tasks or evidence placeholders.

---

These scenarios illustrate: **Agents** (LLM + context) do the reasoning and conversation; **tools** (query_find, query_save, obligations_suggest, domain saves) do the concrete work. The Obligations Agent scenario matches the existing “suggest obligations” flow and extends it so the agent can call the save tool(s) to persist accepted suggestions.

---

## Considerations and Possible Gaps

Details you might want to pin down as you implement:

### Orchestration: who runs the agent loop?

- **Single-turn (simple)**: Frontend or backend calls the LLM once with context + user input; LLM returns suggestions (or a reply). No tool calls in that round. For "Accept selected," the backend (or frontend) maps suggestions to Obligation JSON and calls the save tool(s) directly — no second LLM round. This matches the Obligations scenario as written: suggest = one agent invocation; persist = explicit tool call (orchestrated by backend/frontend, not by the LLM deciding to call a tool).
- **Multi-turn with tool use**: The agent (LLM) can *request* tool calls (e.g. "I want to call query_save with this payload"). Then something — a **backend orchestrator** or the client — must: (1) run the LLM, (2) parse its tool-call requests, (3) call `POST /api/agent/execute` for each, (4) feed results back to the LLM, (5) repeat until the LLM returns a final answer. That requires an **orchestrator** (often in the backend) and a clear contract for "LLM says: call tool X with args Y." If you don't implement that, keep the Obligations flow as single-turn: agent produces suggestions; backend/frontend does the save when user accepts.

**Suggestion**: Decide up front whether "invoke agent" is single-turn (agent returns content only; caller does tool calls) or multi-turn (orchestrator runs LLM + executes tool calls the LLM requests). Document which flow each scenario uses.

---

### Security and identity

- **Realm**: Agent invocation should be realm-scoped (which realm? Current user's realm? Request body?). Same for tool execution — we already have realm in TenantAgentConfig and in execute arguments.
- **Run-as**: Today tool execution can use `runAsUserId` (tenant config). When *the agent* is invoked, does it run as the **current user** or as a **service/bot user**? If the agent later calls tools, do those run as the same principal? Clarify in the design.
- **Permissions**: Who can create/edit agents? Who can invoke a given agent? Consider functional permissions (e.g. `agent:create`, `agent:invoke:<refName>`) or role-based access.
- **Per-realm agents**: Are agents stored per realm (each realm has its own Obligations Agent config) or global (one Obligations Agent, many realms use it)? Per-realm is safer for multi-tenant.

---

### Context (prompts): variables and limits

- **Template variables**: For the Obligations Agent, context may need to be parameterized: e.g. "Entity id: {{entityId}}, Jurisdiction ids: {{jurisdictionIds}}." Decide whether prompt content supports placeholders substituted at invoke time (entity id, jurisdiction ids, realm, etc.).
- **Token limits**: Long context = more tokens and cost. Consider a soft limit or warning in the UI when context (prompts) exceeds N tokens; document how you estimate (e.g. ~4 chars per token).
- **Versioning**: If you change an agent's context, do you version it (e.g. "this run used agent version 2") for audit or reproducibility? Optional but useful for compliance.

---

### Mapping suggestions to Obligation and validation

- **Schema**: The agent (or the code that maps suggestions to save payloads) needs to produce JSON that matches the **Obligation** entity (and references). We have `GET /api/agent/schema/Obligation` (or equivalent); document that the orchestrator or mapping layer uses this schema to build valid payloads.
- **Validation**: Validate agent-produced (or mapped) JSON against the Obligation schema before calling the save tool. On validation failure: return errors to the user, don't persist partial data; optionally allow "fix and retry" for invalid rows.
- **Partial failure**: If the user accepts 10 suggestions and save fails for 3 (e.g. duplicate, constraint), do you: (a) persist 7 and return "3 failed" with reasons, (b) roll back all and return error, or (c) retry the 3? (a) is common; document the choice.
- **Idempotency**: If the user clicks "Accept selected" twice, or the request is retried, consider idempotency keys or "only create if not already present" so you don't duplicate obligations.

---

### Observability and audit

- **Audit trail**: Log who invoked which agent, when, with what inputs (entity id, jurisdiction ids, realm), and — if tools were called — which tools with what arguments and results. Important for compliance and debugging.
- **Token usage**: Log tokens in/out per agent invocation (and per realm) so you can monitor cost and quotas.
- **Trace/session ids**: We already have `sessionId` and `traceId` on the execute request. Use the same (or extend) for "invoke agent" so you can correlate agent run to tool calls to persistence.

---

### Error handling and resilience

- **LLM failures**: Timeout, rate limit, or provider error — how does the UI behave? Retry? Fallback message? For Obligations, you could fall back to template-based suggest (existing `POST /psa/obligations/suggest`) when the agent is unavailable; document that as an optional fallback.
- **Tool execution failure**: If the save tool returns an error for one obligation, see "Partial failure" above. Surface which items failed and why (e.g. "Obligation X failed: duplicate refName").

---

### UI placement and confirmation

- **Where to invoke**: Obligations Agent can be invoked from Jurisdiction or Legal Entity detail (e.g. "Suggest obligations" button). Other agents might live in a dedicated "Agent" or "Assist" surface (e.g. chat-style). Decide per agent type.
- **Confirmation before persist**: The design already keeps humans in the loop: user reviews suggestions and clicks "Accept selected" before any save. Don't auto-persist on behalf of the agent without explicit user action. If you later add multi-turn agents that *request* tool calls, consider "agent wants to call query_save — allow?" for write tools.

---

### Alternative: hybrid suggest (agent + domain tool)

- The Obligations Agent could *call* the existing **obligations_suggest** tool (template-based) first to get a baseline list, then use the LLM to enrich, filter, or add more suggestions. That hybrid (tool for template suggestions + LLM for refinement) can reduce hallucination and cost. Optional; document as an alternative to "pure LLM suggests."

---

### Cost and limits (optional)

- **Per-realm or per-tenant quotas**: e.g. max N agent invocations per day, or max tokens per month, to avoid runaway cost.
- **Rate limits**: Throttle "invoke agent" per user or per realm to prevent abuse.

---

None of these are blocking; they are details to clarify as you implement so the design stays consistent and auditable.

---

## Implementation Status

- **Phase 1 (done)**: Agent entity (name, refName, llmConfigRef, context = list of PromptStep, enabledTools), AgentRepo, CRUD under `/psa/agents` (list, get by id, get by refName, create, update, delete). Frontend: agents list page, create agent page, edit agent page (context = ordered prompt steps, add/reorder/remove). Nav: Security → Agents.
- **Phase 2 (pending)**: Invoke agent endpoint + LLM client (e.g. POST /psa/agents/invoke or /psa/agents/{id}/invoke) so the Suggestions Agent can be invoked with entity id, jurisdiction ids; LLM returns structured suggestions.
- **Phase 3 (pending)**: Wire Obligations flow: Suggest → invoke Suggestions Agent (or fallback to existing POST /psa/obligations/suggest); Accept selected → Persistence Agent or deterministic mapping + save.

---

## Summary

- **Your framing fits**: **Agent** = LLM + context (the thing that thinks); **Tools** = specialized tasks the agent leverages (query_find, etc.). That matches industry usage and our existing `/api/agent` path.
- **Today**: We have **tools** (callable ops) and per-realm LLM config, but no **Agent** (LLM + context) entity, API, or UI.
- **Recommendation**: Use **Agent** for "LLM + context." Add Agent (model, API, UI). Keep **tools** as the callable capabilities the agent uses; no rename. Optionally add per-Agent "enabled tools" later.

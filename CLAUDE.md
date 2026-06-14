## Helix Brain — AI-Powered Codebase Intelligence

You have access to **Helix Brain** via MCP tools (names starting with `mcp__helixai__`).
These tools give you structural understanding of this codebase and learned patterns
from past sessions. **Use them BEFORE grep/read.**

### This Codebase

- **Quantum Framework** — Open-source Java framework with ontology engine, action enablement, and Quarkus integration
- **Language**: Java (Quarkus)
- **Build**: Maven (multi-module: `framework/`, `quantum-action-enablement-models/`, `quantum-action-enablement-quarkus/`, `quantum-cli/`)
- **Source**: `*/src/main/java/` across modules
- **Code index**: Pre-built at `~/.congi/code_index/framework/`
- **Related repos**: `quantum-enterprise` (commercial extension), `framework-cognito-provider`

### Your most useful tools (use these first)

| Tool name | What it does |
|-----------|-------------|
| `mcp__helixai__code_index_query` | Search code by symbol name, file, or pattern. Use INSTEAD of Grep. Pass `codebase_id: "end2endlogic-com-quantum-framework"`. |
| `mcp__helixai__code_symbology_plan_context` | Get coupling map, quality scores, helix patterns for files before planning changes. |
| `mcp__helixai__brain_session_create` | Start a tracked session. Do this at the beginning of every task. |
| `mcp__helixai__brain_session_search` | Find prior sessions by topic. Restore context from past work. |
| `mcp__helixai__brain_memory_search` | Search learned operations and domain knowledge. |
| `mcp__helixai__brain_helix_lookup` | Look up what approaches worked before for similar problems. |
| `mcp__helixai__code_index_build` | Re-index this codebase after significant changes. |
| `mcp__helixai__code_index_list` | List all indexed codebases to see what's available. |
| `mcp__helixai__code_symbology_learn` | Record outcomes after code changes so the system learns. |

### IMPORTANT: Always pass codebase_id

When calling `mcp__helixai__code_index_query` or other code tools, pass
`codebase_id: "end2endlogic-com-quantum-framework"` so the correct index is loaded.

You can also query other related codebases:
- `codebase_id: "end2endlogic-com-quantum-enterprise"` — commercial extensions
- `codebase_id: "b2bi-server"` — B2Bi integration
- `codebase_id: "end2endlogic-com-psa-app"` — PSA application
- `codebase_id: "congi-sparse"` — Helix Brain itself

### Mandatory: session protocol

**Start of every task:**
1. `mcp__helixai__brain_session_create` with `domain: "engineering"`
2. `mcp__helixai__brain_session_search` with a keyword for the current task
3. `mcp__helixai__code_symbology_plan_context` with files you plan to modify

**End of every task:**
1. `mcp__helixai__code_symbology_learn` if you changed code
2. `mcp__helixai__brain_session_attribution` to show what Helix did
3. `mcp__helixai__brain_session_destroy` with `persist_learnings: true`

### Tool preference

| Task | Use this Helix tool | NOT this |
|------|-------------------|----------|
| Find where a class is used | `mcp__helixai__code_index_query` | `Grep` for the class name |
| Find callers of a method | `mcp__helixai__code_index_query` | `Grep` for method name |
| Understand file dependencies | `mcp__helixai__code_symbology_plan_context` | Reading 20 files |
| Evaluate dependency health | `mcp__helixai__code_dependency_evaluate` | Manual pom.xml review |
| Search domain knowledge | `mcp__helixai__brain_memory_search` | Generic LLM knowledge |
| Look up what worked before | `mcp__helixai__brain_helix_lookup` | Starting from scratch |

### Dependency boundary (from AGENTS.md)

- The open-source framework must NOT depend on `quantum-enterprise`
- `quantum-enterprise` is optional and commercially licensed
- Keep enterprise-specific behavior outside the framework unless fully optional and decoupled

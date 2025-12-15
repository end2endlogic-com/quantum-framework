# Ontology REST API (quantum-ontology-mongo)

This module exposes read-only REST endpoints to explore your ontology (TBox) and instance relationships (ABox), with a JointJS-ready graph format for easy browser visualization.

The API is secured and designed to integrate with the broader Quantum Framework. This guide explains how to call each endpoint and render graphs with JointJS.

---

## Quick start
- Base path: `/ontology`
- Media type: `application/json`
- Security: Bearer JWT, roles `user` or `admin`
- Multi-tenancy header: `X-Realm: <tenant-id>` (required for instance graph endpoints and most repo-backed calls)

Curl example (replace token and realm):
```
curl -H "Authorization: Bearer <JWT>" \
     -H "X-Realm: demo" \
     http://localhost:8080/ontology/summary
```

If running in test/dev with JWT disabled, the Authorization header may not be required. In production, it is required.

---

## Endpoints overview

- TBox (schema/registry)
  - GET `/ontology/registry` – Full TBox snapshot (properties, property chains; classes best-effort if available).
  - GET `/ontology/classes` – List of classes (best-effort inferred from property domains/ranges).
  - GET `/ontology/classes/{name}` – Class details by name.
  - GET `/ontology/properties` – List all properties.
  - GET `/ontology/properties/{name}` – Property details by name.
  - GET `/ontology/propertyChains` – List property chains.
  - GET `/ontology/graph/jointjs` – Graph view of the TBox formatted as JointJS cells[].
  - GET `/ontology/summary` – Small counts summary useful for UI.

- ABox (instances/edges)
  - GET `/ontology/graph/instances/jointjs` – Instance neighborhood graph as JointJS cells[] centered on a given `src` id and `type`.

---

## Headers and security

- `Authorization: Bearer <JWT>` – Required in most deployments.
- `X-Realm: <tenant-id>` – Required for instance graph queries (ABox); optional for purely in-memory TBox demos. The value should match your tenant/realm.

Roles required: `user` or `admin`.

OpenAPI security scheme: `bearerAuth` (HTTP bearer JWT). If your app publishes OpenAPI, you can browse the endpoints at `/q/openapi` or via Swagger UI if enabled.

---

## TBox (schema) APIs

### GET /ontology/registry
Returns a snapshot of the TBox. If your OntologyRegistry does not expose classes directly, classes may be empty while properties and chains are provided.

Example:
```
curl -H "Authorization: Bearer $TOKEN" \
     http://localhost:8080/ontology/registry
```
Response (truncated):
```json
{
  "classes": { },
  "properties": {
    "knows": {
      "name": "knows",
      "domain": "Person",
      "range": "Person",
      "inverse": false,
      "inverseOf": null,
      "transitive": false,
      "symmetric": true,
      "functional": false,
      "subPropertyOf": []
    }
  },
  "propertyChains": []
}
```

### GET /ontology/classes
Returns a best-effort list of classes inferred from property domains/ranges. Optional filter `contains`.

Params:
- `contains` (optional): substring filter on class names.

Example:
```
curl http://localhost:8080/ontology/classes
```
Response:
```json
[
  { "name": "Agent", "parents": [], "disjointWith": [], "sameAs": [] },
  { "name": "Person", "parents": ["Agent"], "disjointWith": [], "sameAs": [] }
]
```

### GET /ontology/classes/{name}
Fetch details for a class by name.
```
curl http://localhost:8080/ontology/classes/Person
```

### GET /ontology/properties
List all properties.
```
curl http://localhost:8080/ontology/properties
```

### GET /ontology/properties/{name}
Fetch a property by name.
```
curl http://localhost:8080/ontology/properties/knows
```

### GET /ontology/propertyChains
List property chain axioms.
```
curl http://localhost:8080/ontology/propertyChains
```

### GET /ontology/graph/jointjs
Returns a JointJS-ready graph view of the TBox.

Params:
- `focus` (optional): name of a class or property to prioritize (layout hint only for now).
- `depth` (optional, default `1`): expansion depth (reserved for future use; current builder lays out a flat graph).
- `include` (optional, default `classes,properties,chains`): comma-separated toggles.

Example:
```
curl http://localhost:8080/ontology/graph/jointjs
```
Response:
```json
{
  "cells": [
    { "type": "standard.Rectangle", "id": "Class:Person", "position": { "x": 60, "y": 60 }, "size": { "width": 160, "height": 40 }, "attrs": { "label": { "text": "Person" }, "root": { "data-type": "class" } } },
    { "type": "standard.Rectangle", "id": "Prop:knows", "position": { "x": 280, "y": 60 }, "size": { "width": 180, "height": 34 }, "attrs": { "label": { "text": "knows" }, "root": { "data-type": "property" } } },
    { "type": "standard.Link", "id": "Edge:domain:knows", "source": { "id": "Prop:knows" }, "target": { "id": "Class:Person" }, "labels": [ { "attrs": { "text": { "text": "domain" } } } ] }
  ]
}
```

---

## ABox (instances) API

### GET /ontology/graph/instances/jointjs
Returns a JointJS cells[] graph representing the neighborhood of a specific instance id (`src`) with the given `type`. Both outgoing and incoming edges can be included.

Headers:
- `X-Realm`: required tenant/realm id.

Query params:
- `src` (required): the focus instance id.
- `type` (required): type/class of the focus instance (validated against srcType for outgoing, dstType for incoming).
- `direction` (optional): `out` | `in` | `both` (default: `out`).
- `p` (optional, repeatable): restrict to these predicates. Example: `&p=knows&p=memberOf`.
- `limit` (optional): maximum number of edges to include (default: 200).

Examples:
```
# Outgoing only
curl -H "X-Realm: demo" \
  "http://localhost:8080/ontology/graph/instances/jointjs?src=12345&type=Person&direction=out&limit=100"

# Both directions, filtered by predicate
curl -H "X-Realm: demo" \
  "http://localhost:8080/ontology/graph/instances/jointjs?src=12345&type=Person&direction=both&p=knows"
```

Response (shape):
```json
{
  "cells": [
    { "type": "standard.Rectangle", "id": "Inst:Person:12345", "size": {"width":180,"height":60},
      "position": {"x":60,"y":60},
      "attrs": { "label": {"text": "Person\n12345"}, "root": { "data-type": "instance", "data-class": "Person", "stroke": "#1976d2" } }
    },
    { "type": "standard.Rectangle", "id": "Inst:Person:67890", "size": {"width":180,"height":50},
      "position": {"x":280,"y":60},
      "attrs": { "label": {"text": "Person\n67890"}, "root": { "data-type": "instance", "data-class": "Person" } }
    },
    { "type": "standard.Link", "id": "Edge:12345:knows:67890",
      "source": {"id": "Inst:Person:12345"},
      "target": {"id": "Inst:Person:67890"},
      "labels": [ { "attrs": { "text": { "text": "knows" } } } ],
      "attrs": { "line": { "stroke": "#34495e" } },
      "data": { "inferred": false }
    }
  ]
}
```

Inferred vs direct predicates:
- Direct edges: solid stroke (`attrs.line.stroke` solid color), label is the predicate name.
- Inferred edges: dashed gray stroke (`strokeDasharray: "5,5"`, `stroke: "#9e9e9e"`) and label gains the suffix `" (inferred)"`.
- Programmatic consumers can test `cell.data.inferred === true` for styling.

---

## Rendering with JointJS

Minimal example to render the returned graph:
```html
<div id="paper" style="width: 1200px; height: 800px;"></div>
<script type="module">
  import * as joint from 'https://cdn.jsdelivr.net/npm/@joint/core@4.0.6/dist/joint.core.esm.js';
  async function load() {
    const res = await fetch('/ontology/graph/jointjs'); // or /ontology/graph/instances/jointjs?... with headers
    const { cells } = await res.json();
    const graph = new joint.dia.Graph();
    graph.fromJSON({ cells });
    const paper = new joint.dia.Paper({
      el: document.getElementById('paper'),
      model: graph,
      width: 1200,
      height: 800,
      gridSize: 10,
      drawGrid: true
    });
    // Optional: apply a layout once cells are loaded
    // joint.layout.DirectedGraph.layout(graph, { rankSep: 60, nodeSep: 40, edgeSep: 40 });
  }
  load();
</script>
```

Tips:
- The server assigns basic grid positions; clients may re-layout.
- Use `data` and `attrs.root.data-*` fields to customize node shapes and styling by type (class vs property vs instance).

---

## Advanced Features

### Ontology Validation
The framework validates TBox definitions including:
- Reference integrity (all classes/properties exist)
- Cycle detection in property and class hierarchies
- Property chain validation (no transitive properties, minimum 2 members)
- Transitive property constraints (domain == range)

See [ONTOLOGY_VALIDATION.md](../quantum-ontology-core/ONTOLOGY_VALIDATION.md) for details.

### Closure Methods
Query transitive closures efficiently:
```bash
GET /ontology/properties/{name}/superProperties
GET /ontology/properties/{name}/subProperties
GET /ontology/properties/{name}/inverse
GET /ontology/classes/{name}/ancestors
GET /ontology/classes/{name}/descendants
```

### TBox Hashing
Stable SHA-256 hashes for change detection:
```bash
GET /ontology/hash              # Current TBox hash
GET /ontology/version           # Includes yamlHash and tboxHash
```

### Inferred Properties
Properties marked `inferred: true` in YAML are:
- Read-only in REST responses
- Visually distinct in graphs (dashed lines)
- Automatically marked for chain-implied properties

### Materialization
The `MaterializationEngine` applies ontology rules (subPropertyOf, inverses, transitivity, chains) using fixpoint iteration. See documentation for integration with PostPersistHook.

---

## Troubleshooting

- 401/403 errors: Ensure a valid JWT and role `user` or `admin`.
- 400 on instance graph: Provide both `src` and `type` query params and include `X-Realm` header.
- Empty classes list: The built-in `OntologyRegistry` may not expose classes; properties and domains/ranges still render meaningful graphs.
- Large graphs: Use `include` filters on TBox graph and `p` + `limit` on instance graphs to bound payload size.
- Validation errors: Check YAML syntax and ensure all referenced classes/properties exist. Cycle detection will identify circular hierarchies.

---

## Versioning and compatibility

This API is part of the Quantum Framework (module: `quantum-ontology-mongo`). Endpoints and payload shapes are intended to remain backward compatible; styling metadata may evolve. Check your deployed OpenAPI for authoritative documentation.

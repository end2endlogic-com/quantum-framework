# Design: Text Search in BIAPI Query Language

## Overview
This document proposes adding MongoDB text search support to the existing BIAPI (ANTLR) query language so that `$text` queries can be composed with other filters (eq/in/regex/etc.) and remain consistent across Morphia (database-side) and in-memory evaluation paths. The intent is to keep the grammar backwards compatible, introduce a clear syntax for text search, and ensure validation and execution parity across listeners.

## Goals
- Add a **text search expression** to the BIAPI grammar that composes with existing `&&`/`||` logic.
- Translate the new expression to MongoDB `$text` via Morphia filters.
- Provide a deterministic in-memory fallback for test and policy evaluation.
- Preserve existing query semantics and avoid breaking changes.

## Non-Goals
- Replacing the existing regex/wildcard search semantics.
- Supporting multiple `$text` expressions in a single query (MongoDB limitation).
- Implementing Atlas Search or advanced scoring/ranking features.

## Proposed Syntax
Introduce a function-style expression:

```
text("search terms")
```

### Examples
```
text("john doe") && status:Assigned
(text("priority")) || ownerId:@@5f3b...
text(${"query"}) && type:^ ["user", "group"]
```

### Rationale
- Mirrors existing function-style expressions (`hasEdge`, `expand`).
- Avoids ambiguity with field-based comparisons.
- Natural mapping to MongoDB `$text`, which does not target a single field.

## Grammar Changes (BIAPIQuery.g4)
Add a `textExpr` to `allowedExpr` and introduce a `TEXT` token:

```antlr
allowedExpr: ... | textExpr;
textExpr: TEXT LPAREN value=(STRING|QUOTED_STRING|VARIABLE) RPAREN;
TEXT: 'text';
```

> NOTE: Ensure the new token does not conflict with existing `STRING` tokenization rules.

## Listener Updates

### Morphia Query Translation
File: `quantum-morphia-repos/.../QueryToFilterListener.java`
- Add `enterTextExpr(...)` handler.
- Build `Filters.text(value)` and push onto the filter stack.
- Enforce **single text clause** per query. If a second text clause is encountered, raise a validation error.

### Query Validation (Morphia)
File: `quantum-morphia-repos/.../ValidatingQueryToFilterListener.java`
- Track if a text expression has been seen.
- Throw a validation error on the second occurrence.

### In-Memory Predicate
File: `quantum-framework/.../QueryToPredicateJsonListener.java`
- Define a fallback evaluation method for `text(...)`.
- Option A (recommended for parity with existing features):
  - Treat `text("foo bar")` as **tokenized contains-any** across a configured field list.
  - Field list could be supplied by a new `TextSearchConfig` with defaults or per-query override.
- Option B (simpler):
  - Treat `text(...)` as a regex across a concatenated field representation.

### In-Memory Validation
File: `quantum-framework/.../ValidatingQueryToPredicateJsonListener.java`
- Reject multiple `text(...)` occurrences (same rule as Morphia).

## Execution Semantics

### MongoDB / Morphia
- Text search is case-insensitive by default (MongoDB behavior).
- `$text` can be combined with other filters using `Filters.and(...)` or implicit AND in the top-level query.
- MongoDB supports only **one `$text` expression** per query.

### In-Memory Behavior
- Behavior should be clearly documented to avoid mismatches with MongoDB tokenization.
- Recommended: split search string into tokens by whitespace and require **any token match**.
- Perform case-insensitive matches.
- Limit to a configured field list to avoid searching unrelated data.

## Index Requirements
- MongoDB allows **one text index per collection**, which can cover multiple fields.
- The index must be provisioned via existing Morphia annotations and/or migration tooling.
- Ensure documentation includes example index configuration and notes about weights.

## API / Configuration Hooks
- Add a config entry (e.g., `quantum.query.textSearch.fields`) to specify default fields for in-memory evaluation.
- Optionally allow per-query override via a `TextSearchConfig` object passed into `QueryPredicates.compilePredicate(...)`.

## Validation Rules
- Allow `text(...)` inside parentheses and in compound expressions.
- Reject multiple `text(...)` expressions in a single query.
- Reject empty search strings.

## Documentation Updates
- Add `text(...)` to the query language guide with syntax and examples.
- Document limitations (single text clause, MongoDB tokenization differences).
- Mention index requirements and example Morphia annotations.

## Testing Plan
1. **Grammar tests**
   - Parse `text("foo")` alone and with `&&`/`||` operators.
2. **Morphia listener tests**
   - Ensure `text("foo") && status:Active` produces `Filters.text` combined with `Filters.eq`.
   - Ensure duplicate `text(...)` is rejected.
3. **In-memory predicate tests**
   - Verify case-insensitive token matching across configured fields.
   - Validate rejection of duplicate `text(...)`.

## Rollout Plan
1. Update grammar + regenerate ANTLR artifacts if needed.
2. Implement Morphia translation and validation.
3. Implement in-memory predicate + validation.
4. Update docs and add tests.

## Open Questions
- Should we allow phrase search with quotes to map to MongoDB phrase semantics?
- Should we expose `language` or `caseSensitive` flags for text search?
- How should field selection for in-memory evaluation be configured or overridden?

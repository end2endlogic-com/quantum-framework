# Design: Text Search in BIAPI Query Language

## Overview
This document describes MongoDB text search support in the BIAPI (ANTLR) query language so that `$text` queries can be composed with other filters (eq/in/regex/etc.) and remain consistent across Morphia (database-side) and in-memory evaluation paths. The intent is to keep the grammar backwards compatible, introduce a clear syntax for text search, and ensure validation and execution parity across listeners.

## Goals
- Add a **text search expression** to the BIAPI grammar that composes with existing `&&`/`||` logic.
- Translate the new expression to MongoDB `$text` via Morphia filters.
- Provide a deterministic in-memory fallback for test and policy evaluation.
- Preserve existing query semantics and avoid breaking changes (including unquoted `text` as a field/value).

## Non-Goals
- Replacing the existing regex/wildcard search semantics.
- Supporting multiple `$text` expressions in a single query (MongoDB limitation).
- Implementing Atlas Search or advanced scoring/ranking features.
- Perfect parity with MongoDB language-specific stemming and stop-word removal.

## Proposed Syntax
Introduce a function-style expression:

```
text("search terms")
```

### Examples
```
text("john doe") && status:Assigned
text("priority") && (status:"OPEN" || status:"PENDING")
text(${query}) && type:^ ["user", "group"]
type:text
text:active
```

**Note**: `text(...)` cannot be used inside OR expressions. Use AND to combine text search with other conditions, and place OR logic in a separate grouping.

### Rationale
- Mirrors existing function-style expressions (`hasEdge`, `expand`).
- Avoids ambiguity with field-based comparisons.
- Natural mapping to MongoDB `$text`, which does not target a single field.

## Grammar Changes (`quantum-models/src/main/antlr4/com/e2eq/framework/grammar/BIAPIQuery.g4`)
Add `textExpr` to `allowedExpr` and introduce a `TEXT` token **before** `STRING` so the keyword is recognized for `text(...)`. Because `text` is a common English word, accept the `TEXT` token wherever a free identifier (`STRING`) may appear as a field name or unquoted value:

```antlr
allowedExpr: inExpr | basicExpr | nullExpr | existsExpr | booleanExpr | notExpr
  | regexExpr | elemMatchExpr | hasEdgeExpr | hasOutgoingEdgeExpr
  | hasIncomingEdgeExpr | expandExpr | textExpr;

textExpr: TEXT LPAREN value=(STRING|TEXT|QUOTED_STRING|VARIABLE) RPAREN;
TEXT: 'text';

// Examples of identifier positions that also accept TEXT:
// existsExpr: field=(STRING|TEXT) op=EXISTS;
// basicExpr: field=(STRING|TEXT) ... value=(STRING|TEXT|VARIABLE|OID) ...
// valueListExpr values include TEXT so type:^ [text] works.
```

> **Keyword collision**: Without accepting `TEXT` in field/value positions, queries such as `type:text` or `text:active` would fail to parse. Accepting `TEXT` as an identifier keeps those queries working; `getText()` still yields `"text"`.

## Listener Updates

### Morphia Query Translation
File: `quantum-morphia-repos/src/main/java/com/e2eq/framework/model/persistent/morphia/QueryToFilterListener.java`
- Add `enterTextExpr(...)` handler.
- Build `Filters.text(value)` and push onto the filter stack.
- Treat lexer token `TEXT` like `STRING` when coercing field values / IN-list members.
- Enforce **single text clause** per query with `IllegalStateException` and a message that mentions the MongoDB one-`$text` limit.
- Reject `text(...)` nested inside NOT, OR, or elemMatch (see Validation Rules).

### In-Memory Predicate
File: `quantum-framework/src/main/java/com/e2eq/framework/query/QueryToPredicateJsonListener.java`
(package: `com.e2eq.framework.query.runtime`)
- Define a fallback evaluation method for `text(...)`.
- Implement `text("foo bar")` as **tokenized contains-any** with **whole-word** matching across textual values in the JSON payload.
- Perform case-insensitive matches.
- Enforce the same nesting / multiplicity rules as Morphia for execution parity.

## Execution Semantics

### MongoDB / Morphia
- Text search is case-insensitive by default (MongoDB behavior).
- `$text` can be combined with other filters using `Filters.and(...)` or implicit AND in the top-level query.
- MongoDB supports only **one `$text` expression** per query.
- Valid: `text("search") && (status:OPEN || status:PENDING)` (OR is not around `$text`).

### In-Memory Behavior
Approximation of MongoDB `$text` (documented divergences):

1. **Tokenization of the search string**
   - Lower-case the string.
   - Split on Unicode whitespace.
   - Strip leading/trailing punctuation from each token.
2. **Matching against field values**
   - Collect all textual values in the JSON payload (recursive).
   - Split each value into words on non-letter/non-digit boundaries (`[^\p{L}\p{N}]+`).
   - Case-insensitive **whole-word equality** (not substring): `text("cat")` matches `"the cat sat"` but **not** `"category"`.
   - Predicate matches if **any** search term equals **any** word in **any** textual field.
3. **Relation to MongoDB `$text`**
   - Does **not** implement stemming, stop-word removal, language-specific tokenization, or text scores.
   - Intent is parity for common tokenized contains-any tests and policy evaluation, not a full search engine clone.

## Index Requirements
- MongoDB allows **one text index per collection**, which can cover multiple fields.
- The index must be provisioned via existing Morphia annotations and/or migration tooling.
- If a `text(...)` / `$text` query runs against a collection **without** a text index, MongoDB returns an error (text index required). Quantum does not pre-validate index existence in the query pipeline today; the MongoDB error is surfaced to the caller. Operators should ensure indexes exist before enabling `text(...)` in production.
- Documentation should include example index configuration and notes about weights.

## API / Configuration Hooks (Future)
- Optional config (Quarkus `application.properties`), e.g.:
  ```properties
  quantum.query.textSearch.fields=title,description,tags
  ```
  to restrict in-memory evaluation to a specific field list (today: all textual fields).
- Optionally allow per-query overrides via a `TextSearchConfig` object passed into `QueryPredicates.compilePredicate(...)`.
- Feature-flagging `text(...)` parsing/execution per environment or tenant is optional for rollout; not required for correctness once tests cover the constraint set.

## Validation Rules
MongoDB requires `$text` to be a top-level query operator. The following are enforced at parse/compile time on **both** Morphia and in-memory listeners:

| Rule | Exception | Message intent |
|------|-----------|----------------|
| Multiple `text(...)` | `IllegalStateException` | Multiple clauses not supported; one `$text` per query |
| Empty / whitespace-only search | `IllegalArgumentException` | `text(...)` value must be non-empty |
| Inside NOT (`!!`) | `IllegalStateException` | Cannot negate `text(...)` |
| Inside OR (`||`) | `IllegalStateException` | Cannot use `text(...)` inside OR |
| Inside elemMatch (`field:{...}`) | `IllegalStateException` | Cannot use `text(...)` inside elemMatch |

Empty-string validation: literals are rejected after trim (`isBlank()`). Variables that resolve to empty/blank at runtime are also rejected. Whitespace-only strings count as empty.

**Valid usage**: `text(...)` combined with `&&` (AND) at the top level is supported because MongoDB allows `$text` alongside other top-level conditions.

## Documentation Updates
- Add `text(...)` to the query language guide with syntax and examples.
- Document limitations (single text clause, nesting rules, word-level in-memory matching, keyword-as-identifier).
- Mention index requirements and missing-index behavior.

## Testing Plan
1. **Grammar tests**
   - Parse `text("foo")` alone and with `&&` / grouped `||`.
   - Parse `type:text`, `text:active`, `type:^ [text]`.
2. **Morphia listener tests**
   - Ensure `text("foo") && status:Active` produces `$text` combined with equality.
   - Ensure duplicate `text(...)`, NOT, OR, and elemMatch nesting are rejected.
   - Ensure keyword-as-identifier queries parse.
3. **In-memory predicate tests**
   - Case-insensitive whole-word matching; `cat` does not match `category`.
   - Reject duplicate / NOT / OR / elemMatch / empty `text(...)`.
   - Accept `text(...) && (a || b)` composition.
4. **Composition with advanced expressions** (where environment supports it)
   - `text("search") && hasEdge(...)` parses; evaluation depends on ontology wiring.

## Rollout Plan
1. Update grammar and regenerate ANTLR artifacts (required for any grammar change).
2. Implement Morphia translation and validation.
3. Implement in-memory predicate + validation (parity with Morphia constraints).
4. Update docs and add tests.
5. Ensure target collections have a text index before enabling `text(...)` in production; be prepared to roll back by stopping use of `text(...)` if query errors spike.

## Open Questions
- Should we allow phrase search with quotes to map to MongoDB phrase semantics?
- Should we expose `language` or `caseSensitive` flags for text search?
- How should field selection for in-memory evaluation be configured or overridden?
- Should we expose text search scoring (MongoDB `$meta: "textScore"`) in the query language, and if so how should it integrate with result ordering and the in-memory path?

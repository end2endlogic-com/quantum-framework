# Seed Pack Reference Resolution Design

## Summary

The seed framework currently supports pack-level dependency ordering through `includes`, but it does not support record-level dependency resolution when a seeded entity contains references to another persisted entity.

That gap is visible in the current implementation:

- `SeedLoader` applies included packs before the current pack, then applies datasets in manifest order, with no graph planning across datasets or records.
- `MorphiaSeedRepository` contains a narrow special case for `parent.entityRefName -> entityId`, but there is no generalized reference resolver for Morphia `@Reference` fields or `EntityReference` value objects.
- `SeedVerificationService` validates payload shape and database id collisions, but it does not verify that referenced targets can actually be resolved.

This design adds a reference-resolution layer to the seed framework so a seed record can safely refer to:

1. an existing record already in the target realm,
2. a record earlier in the same seed pack,
3. a record in an included seed pack, or
4. a record in another applicable seed pack resolved as part of the same apply operation.

The fail-closed behavior is explicit: if a declared reference cannot be resolved from the database or from the staged seed plan, the apply fails with a targeted error.

## Current Source of Truth

The current indexed framework shows the main extension points here:

- `SeedLoader.applyDescriptor()` and `SeedLoader.applyDatasets()` in `quantum-framework/src/main/java/com/e2eq/framework/service/seed/SeedLoader.java`
- `MorphiaSeedRepository.upsertRecord()` and `adaptForModel()` in `quantum-framework/src/main/java/com/e2eq/framework/service/seed/MorphiaSeedRepository.java`
- `SeedVerificationService.verifyDataset()` in `quantum-framework/src/main/java/com/e2eq/framework/service/seed/SeedVerificationService.java`
- dataset manifest structure in `quantum-framework/src/main/java/com/e2eq/framework/service/seed/SeedPackManifest.java`
- `EntityReference` shape in `quantum-models/src/main/java/com/e2eq/framework/model/persistent/base/EntityReference.java`
- Morphia `@Reference` usage example in `quantum-framework/src/test/java/com/e2eq/framework/test/BookModel.java`

## Problem Statement

Today a seed record can carry stable semantic identifiers like `refName`, but Morphia references ultimately persist using `ObjectId`-backed structures. That means seed application needs a deterministic way to translate a logical reference such as:

```json
{
  "author": {
    "entityRefName": "asimov"
  }
}
```

into the concrete persisted shape expected by the target model.

Without that layer, teams must either:

- hardcode `ObjectId` values in seed data,
- manually order data and write glue code per model,
- or accept fragile post-processing.

## Goals

- Support generalized logical references in seed payloads for Morphia-backed models.
- Resolve references by stable business identity, with `refName` as the default key.
- Support cross-pack and cross-dataset dependencies inside a single apply operation.
- Fail closed when a required reference cannot be resolved.
- Surface unresolved references during verification before any writes occur.
- Preserve current save/update behavior by reading an existing entity, merging onto it, and saving the full entity.

## Non-Goals

- No dependency on `quantum-enterprise`.
- No requirement to support arbitrary free-form Mongo document references in phase 1.
- No automatic synthesis of missing target records.
- No implicit "best effort" nulling for required references.

## Proposed Model

### 1. Introduce a logical seed reference contract

Add a small manifest-driven reference contract to `SeedPackManifest.Dataset`.

New field:

```yaml
datasets:
  - collection: BookModel
    file: datasets/books.ndjson
    naturalKey: [refName]
    upsert: true
    modelClass: com.e2eq.framework.test.BookModel
    references:
      - field: author
        targetModelClass: com.e2eq.framework.test.AuthorModel
        lookupBy: refName
        required: true
```

Proposed Java shape:

```java
List<ReferenceBinding> references;

public static final class ReferenceBinding {
    private String field;
    private String targetModelClass;
    private String targetCollection;
    private String lookupBy;
    private Boolean required;
    private Boolean many;
}
```

Notes:

- `field` is the top-level model field path for phase 1.
- `targetModelClass` is preferred for Morphia-backed resolution.
- `targetCollection` is an optional fallback for generic Mongo-backed resolution later.
- `lookupBy` defaults to `refName`.
- `required` defaults to `true`.
- `many` is optional and can be inferred for collection-valued fields, but making it declarative simplifies validation.

Why manifest-driven first:

- We avoid trying to infer target types for every nested field shape up front.
- We support both `@Reference` and `EntityReference` targets explicitly.
- The seed pack remains self-describing and verifiable before persistence.

### 2. Introduce a seed apply plan and reference index

Add a planning step before dataset writes:

- `SeedApplyPlan`
- `SeedDatasetPlan`
- `SeedRecordHandle`
- `SeedReferenceIndex`

Responsibilities:

- flatten all packs participating in one `apply(...)` call, after pack-level include resolution,
- preserve deterministic dataset order,
- index records by logical identity before writing,
- let reference resolution find targets in either:
  - the existing database, or
  - the current in-memory apply plan.

`SeedReferenceIndex` should key planned records by:

- realm
- target model or collection
- lookup field
- lookup value

For phase 1, `lookupBy=refName` is enough to deliver most value cleanly.

### 3. Resolve references before model conversion

Generalize the current `resolveParentReference(...)` logic in `MorphiaSeedRepository` into a dedicated component, for example:

- `SeedReferenceResolver`
- `MorphiaSeedReferenceResolver`

New flow inside `MorphiaSeedRepository.upsertRecord(...)`:

1. sanitize/adapt top-level fields,
2. resolve declared references using the `SeedReferenceResolver`,
3. convert the adapted map into the target Morphia model,
4. load existing record by natural key or id,
5. merge onto the existing entity and save.

This keeps reference resolution in the same place the repository already performs model-aware adaptation.

### 4. Support two target shapes in phase 1

#### A. `EntityReference`

Input seed shape:

```json
{
  "credentialUserIdPasswordRef": {
    "entityRefName": "admin-user"
  }
}
```

Resolution result:

- populate `entityId`
- populate `entityRefName` if omitted
- populate `entityDisplayName` when available
- populate `entityType` when available
- preserve any caller-provided `additionalFields`

#### B. Morphia `@Reference`

Input seed shape:

```json
{
  "author": {
    "refName": "asimov"
  }
}
```

or

```json
{
  "authors": [
    { "refName": "asimov" },
    { "refName": "clarke" }
  ]
}
```

Resolution result before `convertValue(...)`:

- replace each logical reference stub with a minimal model-shaped map containing the target `id`
- allow Morphia/Jackson conversion to materialize a model instance whose `id` drives persisted DBRef or id-only reference encoding

This is better than storing raw DBRef documents by hand because it stays aligned with Morphia mapping behavior.

## Resolution Algorithm

For each record and each declared reference binding:

1. Read the source field from the seed record.
2. If the value is null or empty:
   - if `required=true`, emit an error,
   - if `required=false`, leave it null.
3. Extract the logical lookup value.
4. Attempt resolution in this order:
   - already-persisted record in the target realm,
   - record already written earlier in this apply run,
   - record present in the apply plan but not yet written.
5. If the target exists only in the apply plan and has not been written yet:
   - ensure the overall plan orders the target dataset before the dependent dataset,
   - if the dependency is intra-dataset, allow record-level deferred resolution or require manifest order plus a second pass.
6. If the target cannot be resolved:
   - throw `SeedLoadingException` with pack, dataset, record index, field, target type, and lookup value.

## Ordering Strategy

There are two layers of dependency ordering.

### Pack-level

Keep the current `includes` behavior exactly as-is.

### Dataset-level

Build a dataset dependency graph from declared `references`.

If dataset `books` references `authors`, then:

- `authors` must be applied before `books`

Rules:

- if the dependency graph is acyclic, topologically sort datasets before apply,
- if there is a cycle across datasets, fail manifest verification,
- if there is a same-dataset self-reference, handle it with a deferred second pass limited to that dataset.

For phase 1, the cleanest rule set is:

- support acyclic cross-dataset dependencies,
- support self-reference only for `EntityReference`-style objects where target already exists or appears earlier in the dataset,
- fail on unresolved forward self-reference rather than trying to invent ids.

## Verification Enhancements

Extend `SeedVerificationService` so unresolved references appear during verify.

New verify checks:

- `payload.referenceMissingField`
- `payload.referenceUnknownTargetModel`
- `payload.referenceAmbiguousTarget`
- `payload.referenceUnresolved`
- `payload.referenceCyclicDependency`

Verification should use the same `SeedApplyPlan` and `SeedReferenceResolver`, but run in read-only mode:

- inspect all pending packs that participate in the apply,
- build the same reference index,
- report unresolved references before mutation.

This keeps verify and apply behavior consistent.

## Failure Semantics

Fail closed by default.

Cases that should error:

- declared reference field missing when required,
- target model/repository cannot be resolved,
- no record exists in DB or planned seed data,
- multiple records match the same logical identifier,
- cyclic dependency between datasets,
- reference points to a seed pack not included in the current apply set and not already present in DB.

Cases that may warn:

- caller supplies both logical lookup and explicit `entityId` and they disagree,
- optional reference omitted,
- target exists in DB and also in pending seed data with a conflicting logical identity.

## Implementation Shape

### New classes

- `SeedApplyPlan`
- `SeedDatasetPlan`
- `SeedReferenceBinding`
- `SeedReferenceResolver`
- `SeedReferenceResolution`
- `SeedReferenceLookupKey`
- `SeedReferenceException`

### Existing classes to update

- `SeedPackManifest.Dataset`
  - add `references`
- `SeedLoader`
  - build apply plan before `applyDatasets`
  - order datasets by dependency graph
- `SeedLoaderService`
  - wire in resolver/planner beans if needed
- `MorphiaSeedRepository`
  - replace `resolveParentReference(...)` with generalized reference resolution
- `SeedVerificationService`
  - verify unresolved references using the same planner/resolver
- `SeedDatasetValidator`
  - validate presence and basic shape of declared reference payloads

## Backward Compatibility

- Existing seed packs continue to work unchanged.
- The current `parent.entityRefName` behavior should be reimplemented on top of the new resolver, not left as a separate special case.
- `references` in the manifest is optional.
- Seed packs that do not opt in continue using current dataset ordering.

## Recommended Phase Split

### Phase 1

- manifest `references` support
- apply plan across included packs
- Morphia-backed resolution by `refName`
- `EntityReference` and simple `@Reference` support
- verification support
- fail-closed errors

### Phase 2

- generic Mongo document resolution using `targetCollection`
- nested field paths
- collection-valued references with richer diagnostics
- optional alternative lookup keys beyond `refName`

## Test Matrix

Add integration coverage for:

1. same-pack dataset A references dataset B and B is applied first
2. pack A references a record defined in included pack B
3. reference resolves to an already-existing DB record
4. unresolved required reference fails verify and apply
5. ambiguous reference match fails
6. optional reference may remain null
7. `EntityReference` target gets `entityId` populated
8. Morphia `@Reference` target persists correctly using target `id`
9. existing upsert record preserves full persisted payload while changing only intended fields
10. cyclic dataset dependency fails before writes

## Recommendation

Implement this as a planner-plus-resolver enhancement, not as ad hoc field-specific glue in `MorphiaSeedRepository`.

That gives us:

- one place to express record-level dependencies,
- one consistent failure model for verify and apply,
- a clean path to support both `1.4.0-SNAPSHOT` and `1.3.1-SNAPSHOT`,
- and no dependency leakage outside the open-source framework.

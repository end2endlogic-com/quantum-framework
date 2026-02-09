# CSV Import/Export SPI Gap Analysis and Design

## 1. Purpose

This document reviews the Quantum framework's CSV import and export functionality, evaluates the relatively new **RowValueResolver** SPI for per-row processing, identifies the gap between per-row and per-batch processing, and proposes a design to extend the same SPI pattern to support both import and export with batch-level hooks.

**Reference documents:**
- `quantum-docs/src/docs/asciidoc/design/csv-import-enhancements.adoc` – Import enhancements design
- `quantum-docs/src/docs/asciidoc/design/csv-dynamic-attributes-design.adoc` – Dynamic attributes design
- `quantum-framework/src/main/java/com/e2eq/framework/imports/spi/RowValueResolver.java` – Per-row SPI
- `quantum-framework/src/main/java/com/e2eq/framework/util/CSVImportHelper.java` – Import pipeline
- `quantum-framework/src/main/java/com/e2eq/framework/util/CSVExportHelper.java` – Export pipeline

---

## 2. Current State Summary

### 2.1 Import Pipeline Phases

The CSV import operates in multiple phases with batching:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Phase 1: PARSE (row-by-row)                                                  │
│   - SuperCSV CsvDozerBeanReader with CellProcessors                          │
│   - Profile transformations: ValueMap, Lookup, CaseTransform, RegexReplace    │
│   - Type conversion: ParseInt, ParseLong, ParseDouble, ParseEnum, etc.       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Phase 2: ROW PROCESSING (per-row, after parse)                               │
│   - FieldCalculators (inline, named)                                         │
│   - RowValueResolver (per-column mapping with full row data)                 │
│   - DynamicAttributeImportService (if profile enabled)                       │
│   - PreValidationTransformer                                                 │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Phase 3: VALIDATION + INTENT (per-row, per-batch lookup)                     │
│   - Jakarta Bean Validation (validator.validate(bean))                       │
│   - Intent detection: repo.getListFromRefNames(refNames) for batch           │
│   - annotateIntentsAndValidate / annotateIntentsAndValidateWithProfile       │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Phase 4: ERROR COLLECTION                                                    │
│   - ImportRowResult with FieldError (PARSE, VALIDATION, DB)                  │
│   - Persisted to ImportSessionRow (or in-memory for tests)                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Phase 5: COMMIT (batch)                                                      │
│   - commitImport() reads valid rows from session, batches by BATCH_SIZE      │
│   - processBatch() → repo.save(batch)                                        │
│   - Bisects on failure to isolate failing records                            │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Batch Processing Details

| Component | Batch Size | Behavior |
|-----------|------------|----------|
| `BATCH_SIZE` | 1000 | Used in preprocessFlatProperty, importFlatProperty, analyzeCSV, analyzeCSVWithProfile |
| `preProcessBatch` | Per batch | Validates beans, determines INSERT vs UPDATE counts |
| `processBatch` | Per batch | `repo.save(batch)`, bisects on DB failure |
| `annotateIntentsAndValidate` | Per batch | `repo.getListFromRefNames(refNames)` – single bulk lookup for batch |
| `commitImport` | Per batch | Pages through ImportSessionRow, saves in batches of BATCH_SIZE |

### 2.3 Per-Row SPI (RowValueResolver, PreValidationTransformer, FieldCalculator)

| SPI | Scope | When Invoked | Context Available |
|-----|-------|--------------|-------------------|
| **RowValueResolver** | Per-column per-row | After parse, before validation (in analyzeCSVWithProfile) | ImportContext (profile, realm, rowNumber, sessionId), full rowData |
| **PreValidationTransformer** | Per-row | After RowValueResolver, before validation | ImportContext, bean, rowData |
| **FieldCalculator** | Per-row | Before RowValueResolver | ImportContext, bean, rowData |

All three run **per-row only**. There is no SPI for:
- Before/after batch processing
- Pre-loading lookups for a batch
- Cross-row validation or deduplication within a batch
- Batch-level side effects (e.g., audit log, cache flush)

### 2.4 Export Pipeline

| Component | Behavior |
|-----------|----------|
| **CSVExportHelper** | Streams records via CsvDozerBeanWriter |
| **Cell processors** | ParseInt, ParseLong, ParseDouble, Optional, ListCellProcessor, NestedArrayCellProcessor |
| **Profile/SPI** | **None** – no ImportProfile, no RowValueResolver, no equivalent SPI |
| **Configuration** | requestedColumns, preferredColumnNames, quotingStrategy – no ExportProfile |

Export has no equivalent to RowValueResolver for reverse transformations (e.g., export internal ID as display name, or resolve references for human-readable output).

---

## 3. Gap Analysis

### 3.1 Per-Row vs Per-Batch Gap

| Gap | Current | Desired |
|-----|---------|---------|
| **Batch lifecycle hooks** | None | `beforeBatch(batch, context)` / `afterBatch(batch, context)` |
| **Batch-level lookups** | Only intent detection (`getListFromRefNames`) | Resolvers that receive full batch for pre-loading (e.g., load all SKUs once per batch) |
| **Batch-level validation** | Per-row only | Cross-row checks (e.g., no duplicate refNames within batch) |
| **Batch-level side effects** | None | Post-import audit, cache invalidation, notification |

### 3.2 Import vs Export Symmetry Gap

| Gap | Import | Export |
|-----|--------|--------|
| **Profile** | ImportProfile (column mappings, transformations, resolvers) | No ExportProfile |
| **Per-row value transformation** | RowValueResolver | No equivalent – raw field values only |
| **Pre-validation / pre-export** | PreValidationTransformer | No equivalent |
| **SPI discoverability** | CDI `Instance<RowValueResolver>` | N/A |

### 3.3 Unified SPI for Import and Export

The same conceptual SPI could serve both directions:
- **Import**: `resolve(inputValue, rowData, context)` → value for bean field
- **Export**: `format(beanValue, rowData, context)` → string for CSV cell

Current RowValueResolver is import-only. A symmetric export SPI does not exist.

---

## 4. Proposed Design

### 4.1 Design Goals

1. **Add per-batch lifecycle hooks** for import without breaking existing per-row behavior.
2. **Introduce a symmetric export SPI** that mirrors RowValueResolver semantics.
3. **Unify import/export under a common abstraction** where feasible (e.g., shared context, direction-aware resolvers).

### 4.2 ImportBatch Abstraction (Shared by Import and SPI)

A shared `ImportBatch<T>` abstraction eliminates batch-size conflicts: the importer defines the batch size and creates batches; the SPI receives whatever batch the importer gives it. Single source of truth.

```java
package com.e2eq.framework.imports.spi;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shared batch abstraction used by CSVImportHelper and BatchLifecycleHandler.
 * Encapsulates one batch of records with metadata. Batch size is defined by
 * the importer (BATCH_SIZE); the SPI receives batches as-is.
 */
public class ImportBatch<T extends UnversionedBaseModel> {

    private final List<T> beans;
    private final List<Integer> rowNumbers;           // CSV row numbers (1-based)
    private final Map<Integer, String> rawByRowNum;   // raw CSV line by row number
    private final Map<Integer, Map<String, Object>> rowDataByRowNum;

    public ImportBatch(List<T> beans, List<Integer> rowNumbers,
                       Map<Integer, String> rawByRowNum,
                       Map<Integer, Map<String, Object>> rowDataByRowNum) {
        this.beans = beans != null ? List.copyOf(beans) : List.of();
        this.rowNumbers = rowNumbers != null ? List.copyOf(rowNumbers) : List.of();
        this.rawByRowNum = rawByRowNum != null ? Map.copyOf(rawByRowNum) : Map.of();
        this.rowDataByRowNum = rowDataByRowNum != null ? Map.copyOf(rowDataByRowNum) : Map.of();
    }

    public List<T> getBeans() { return beans; }
    public List<Integer> getRowNumbers() { return rowNumbers; }
    public Map<Integer, String> getRawByRowNum() { return rawByRowNum; }
    public Map<Integer, Map<String, Object>> getRowDataByRowNum() { return rowDataByRowNum; }
    public int size() { return beans.size(); }
}
```

### 4.3 New SPI: BatchLifecycleHandler

```java
package com.e2eq.framework.imports.spi;

/**
 * SPI for batch-level processing during CSV import.
 * Invoked once per batch before validation and before commit.
 * Receives ImportBatch - the same abstraction used by the importer.
 * Implementations are discovered via CDI and referenced by name in ImportProfile.
 */
public interface BatchLifecycleHandler {

    String getName();

    /**
     * Called before a batch is validated and saved.
     * Use for: batch API calls (e.g., batch geocode), pre-loading lookups,
     * cross-row validation, augmenting beans (e.g., lat/long).
     *
     * @param batch the ImportBatch (beans + row metadata); augment beans in-place
     * @param context import context (realm, session, profile)
     * @return result indicating success, partial failure, or abort
     */
    BatchResult beforeBatch(ImportBatch<? extends UnversionedBaseModel> batch, ImportContext context);

    /**
     * Called after a batch is successfully saved.
     * Use for: audit logging, cache invalidation, notifications.
     */
    default void afterBatch(ImportBatch<? extends UnversionedBaseModel> batch, ImportContext context) {
        // No-op by default
    }

    default boolean appliesTo(Class<?> targetClass) {
        return true;
    }

    default int getOrder() {
        return 100;
    }

    class BatchResult {
        private final boolean success;
        private final List<BatchRowError> rowErrors;

        public static BatchResult success() { ... }
        public static BatchResult error(String message) { ... }
        public static BatchResult partial(List<BatchRowError> errors) { ... }

        public record BatchRowError(int batchIndex, String field, String message) {}
    }
}
```

**Integration points:**
- `CSVImportHelper.analyzeCSVWithProfile`: build `ImportBatch` from batchBeans/batchRowNums/batchRawByRowNum/batchRowDataByRowNum; before `annotateIntentsAndValidateWithProfile`, invoke `BatchLifecycleHandler.beforeBatch(batch, context)`.
- `CSVImportHelper.commitImport` / `processBatch`: after successful `repo.save(batch)`, invoke `BatchLifecycleHandler.afterBatch(batch, context)`.

### 4.4 New SPI: RowValueFormatter (Export)

```java
package com.e2eq.framework.exports.spi;

import java.util.Map;

/**
 * SPI for formatting field values during CSV export.
 * Mirror of RowValueResolver for the export direction.
 */
public interface RowValueFormatter {

    String getName();

    /**
     * Format a bean field value for CSV export.
     *
     * @param beanValue the value from the bean
     * @param rowData all field values from the current record (field name -> value)
     * @param context export context (profile, realm, row index)
     * @return formatted string for the CSV cell
     */
    FormatResult format(Object beanValue, Map<String, Object> rowData, ExportContext context);

    default boolean appliesTo(Class<?> targetClass) {
        return true;
    }

    default int getOrder() {
        return 100;
    }

    class FormatResult {
        public static FormatResult value(String formatted);
        public static FormatResult skip();  // omit this row from export
        public static FormatResult error(String message);
    }
}
```

**ExportContext** mirrors ImportContext: profile (ExportProfile), targetClass, realmId, rowIndex, sessionId.

### 4.5 Unified Row Processor (Optional Direction-Aware)

To allow a single implementation to handle both import and export, a direction-aware interface can wrap RowValueResolver and RowValueFormatter:

```java
/**
 * Optional unified SPI for row-level processing in both import and export.
 * Implementations can implement one or both directions.
 */
public interface RowProcessor {

    String getName();

    /** Import direction: resolve CSV value to bean value */
    default RowValueResolver.ResolveResult resolveImport(String inputValue,
                                                         Map<String, Object> rowData,
                                                         ImportContext context) {
        return RowValueResolver.ResolveResult.passthrough(inputValue);
    }

    /** Export direction: format bean value for CSV */
    default RowValueFormatter.FormatResult formatExport(Object beanValue,
                                                        Map<String, Object> rowData,
                                                        ExportContext context) {
        return RowValueFormatter.FormatResult.value(beanValue != null ? beanValue.toString() : "");
    }
}
```

Adapters can bridge `RowProcessor` to `RowValueResolver` and `RowValueFormatter` for backward compatibility.

### 4.6 ExportProfile (New)

A new persistent entity `ExportProfile` analogous to `ImportProfile`:

| Field | Purpose |
|-------|---------|
| targetType | Entity class name |
| columnMappings | Source field → CSV column, with optional formatterName |
| formatterNames | List of RowValueFormatter names to apply |
| globalFormatOptions | Case, date format, etc. |

Export would use ExportProfile when provided, otherwise fall back to current behavior (requestedColumns, preferredColumnNames).

### 4.7 Integration Summary

| Phase | Import (current) | Import (proposed) | Export (current) | Export (proposed) |
|-------|------------------|-------------------|------------------|-------------------|
| Parse / Serialize | Cell processors | Same | Cell processors | Same |
| Per-row | RowValueResolver, PreValidationTransformer | Same | — | RowValueFormatter |
| Per-batch | — | BatchLifecycleHandler | — | BatchLifecycleHandler (optional) |
| Profile | ImportProfile | Same | — | ExportProfile |

---

## 5. Implementation Phases

### Phase 1: ImportBatch and BatchLifecycleHandler (Import only)
- Add `ImportBatch<T>` abstraction (shared by importer and SPI).
- Add `BatchLifecycleHandler` SPI and `BatchResult`.
- Extend `ImportProfile` with `batchLifecycleHandlerNames` (list).
- Refactor `CSVImportHelper` to use `ImportBatch` and invoke `BatchLifecycleHandler.beforeBatch` before validation.
- Invoke `BatchLifecycleHandler.afterBatch` after successful `processBatch`.
- Add integration tests for batch hooks.

### Phase 2: RowValueFormatter and ExportContext
- Add `exports.spi` package: `RowValueFormatter`, `ExportContext`.
- Add `ExportProfile` entity (or extend ImportProfile with direction).
- Integrate `RowValueFormatter` in `CSVExportHelper` when ExportProfile is provided.
- Add integration tests for export formatting.

### Phase 3: ExportProfile and REST
- Add `ExportProfile` REST CRUD if not present.
- Wire `GET /csv` to use ExportProfile when `profileRefName` query param is present.
- Document ExportProfile and RowValueFormatter in user guide.

### Phase 4: Unified RowProcessor (Optional)
- Add `RowProcessor` interface and adapters.
- Allow ImportProfile/ExportProfile to reference `RowProcessor` by name; adapters delegate to resolveImport/formatExport.

---

## 6. Backward Compatibility

- Existing `RowValueResolver` implementations continue to work unchanged.
- `BatchLifecycleHandler` is optional; if no handlers are configured, behavior is identical to current.
- Export without ExportProfile behaves as today (no formatters).
- `ImportProfile` remains the source of truth for import; new `batchLifecycleHandlerNames` is additive.

---

## 7. Summary

| Gap | Design Response |
|-----|-----------------|
| No batch abstraction | `ImportBatch<T>` shared by importer and SPI; single batch size source of truth |
| No per-batch SPI | `BatchLifecycleHandler` with `beforeBatch` / `afterBatch` |
| Export has no SPI | `RowValueFormatter` + `ExportContext` |
| Import/export asymmetry | `RowValueFormatter` mirrors `RowValueResolver`; optional `RowProcessor` unifies both |
| No ExportProfile | New `ExportProfile` entity with column mappings and formatter names |

This design extends the existing per-row SPI pattern to batch-level processing and establishes export symmetry with import, enabling round-trip CSV workflows with consistent transformation and formatting rules.

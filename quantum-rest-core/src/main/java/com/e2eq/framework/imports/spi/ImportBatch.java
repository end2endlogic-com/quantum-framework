package com.e2eq.framework.imports.spi;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared batch abstraction used by CSVImportHelper and BatchLifecycleHandler.
 * Encapsulates one batch of records with metadata. Batch size is defined by
 * the importer (BATCH_SIZE); the SPI receives batches as-is.
 *
 * <p>Beans are mutable (handlers augment them in-place). The batch structure
 * (list/map references) is immutable to preserve alignment between beans,
 * row numbers, raw lines, and row data.
 */
public class ImportBatch<T extends UnversionedBaseModel> {

    private final List<T> beans;
    private final List<Integer> rowNumbers;
    private final Map<Integer, String> rawByRowNum;
    private final Map<Integer, Map<String, Object>> rowDataByRowNum;

    /**
     * Create an import batch.
     *
     * @param beans the parsed beans (post RowValueResolver, etc.)
     * @param rowNumbers CSV row numbers (1-based) for each bean
     * @param rawByRowNum raw CSV line by row number
     * @param rowDataByRowNum parsed row data (column name to value) by row number
     */
    public ImportBatch(List<T> beans,
                       List<Integer> rowNumbers,
                       Map<Integer, String> rawByRowNum,
                       Map<Integer, Map<String, Object>> rowDataByRowNum) {
        this.beans = beans != null ? List.copyOf(beans) : List.of();
        this.rowNumbers = rowNumbers != null ? List.copyOf(rowNumbers) : List.of();
        this.rawByRowNum = rawByRowNum != null ? Map.copyOf(rawByRowNum) : Map.of();
        // Deep copy rowDataByRowNum values so inner maps are immutable
        Map<Integer, Map<String, Object>> rowDataCopy = new HashMap<>();
        if (rowDataByRowNum != null) {
            for (Map.Entry<Integer, Map<String, Object>> e : rowDataByRowNum.entrySet()) {
                rowDataCopy.put(e.getKey(), e.getValue() != null ? Map.copyOf(e.getValue()) : Map.of());
            }
        }
        this.rowDataByRowNum = Collections.unmodifiableMap(rowDataCopy);
    }

    /**
     * Get the beans in this batch. Beans are mutable; handlers may augment them in-place.
     * The list is unmodifiable (no add/remove) to preserve alignment with row metadata.
     */
    public List<T> getBeans() {
        return Collections.unmodifiableList(beans);
    }

    /** CSV row numbers (1-based) for each bean, in batch order. */
    public List<Integer> getRowNumbers() {
        return Collections.unmodifiableList(rowNumbers);
    }

    /** Raw CSV line by row number. */
    public Map<Integer, String> getRawByRowNum() {
        return Collections.unmodifiableMap(rawByRowNum);
    }

    /** Parsed row data (column name to value) by row number. */
    public Map<Integer, Map<String, Object>> getRowDataByRowNum() {
        return rowDataByRowNum;
    }

    /** Number of records in this batch. */
    public int size() {
        return beans.size();
    }

    /**
     * Get row data for the bean at batch index.
     *
     * @param batchIndex 0-based index into the batch
     * @return row data map, or empty map if not available
     */
    public Map<String, Object> getRowDataAt(int batchIndex) {
        if (batchIndex < 0 || batchIndex >= rowNumbers.size()) {
            return Map.of();
        }
        Integer rowNum = rowNumbers.get(batchIndex);
        Map<String, Object> data = rowDataByRowNum.get(rowNum);
        return data != null ? data : Map.of();
    }
}

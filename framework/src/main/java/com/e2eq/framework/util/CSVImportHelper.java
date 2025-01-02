package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import jakarta.validation.ValidationException;
import jakarta.ws.rs.WebApplicationException;
import org.apache.commons.beanutils.PropertyUtils;
import org.bson.types.ObjectId;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.dozer.CsvDozerBeanReader;
import org.supercsv.io.dozer.ICsvDozerBeanReader;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.AlwaysQuoteMode;
import org.supercsv.quote.NormalQuoteMode;
import org.supercsv.quote.QuoteMode;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;

public class CSVImportHelper {
    private static final int BATCH_SIZE = 1000;

    private static QuoteMode getQuoteMode(String quotingStrategy) {
        if ("QUOTE_ALL_COLUMNS".equalsIgnoreCase(quotingStrategy)) {
            return new AlwaysQuoteMode();
        } else if ("QUOTE_WHERE_ESSENTIAL".equalsIgnoreCase(quotingStrategy)) {
            return new NormalQuoteMode();
        } else {
            throw new ValidationException(format("The value %s is not one of the supported quote strategies: %s or %s",
                    quotingStrategy, "QUOTE_ALL_COLUMNS", "QUOTE_WHERE_ESSENTIAL"));
        }
    }

    private static String[] createFieldMapping(String[] header, List<String> requestedColumns, List<String> preferredColumnNames) {
        if (requestedColumns == null || requestedColumns.isEmpty()) {
            return header;
        }

        String[] fieldMapping = new String[requestedColumns.size()];
        for (int i = 0; i < requestedColumns.size(); i++) {
            String columnName = requestedColumns.get(i);
            if (preferredColumnNames != null && i < preferredColumnNames.size()) {
                String preferredName = preferredColumnNames.get(i);
                if (!preferredName.isEmpty()) {
                    columnName = preferredName;
                }
            }
            fieldMapping[i] = columnName;
        }
        return fieldMapping;
    }

    @FunctionalInterface
    public interface FailedRecordHandler<T> {
        void handleFailedRecord(T record);
    }

    public static class ImportResult<T> {
        private final int importedCount;
        private final int failedCount;

        public ImportResult(int importedCount, int failedCount) {
            this.importedCount = importedCount;
            this.failedCount = failedCount;
        }

        public int getImportedCount() {
            return importedCount;
        }

        public int getFailedCount() {
            return failedCount;
        }
    }

    public static <T extends BaseModel> ImportResult<T> importCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            List<String> preferredColumnNames,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy,
            FailedRecordHandler<T> failedRecordHandler) throws IOException {
        int importedCount = 0;
        int failedCount = 0;

        try (Reader reader = new InputStreamReader(inputStream, charset)) {
            if (mustUseBOM) {
                reader.read(); // Skip BOM character
            }

            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(getQuoteMode(quotingStrategy))
                            .build());

            String[] header = beanReader.getHeader(true);
            if (skipHeaderRow && header == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            String[] fieldMapping = createFieldMapping(header, requestedColumns, preferredColumnNames);

            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            CellProcessor[] processors = new CellProcessor[fieldMapping.length];
            // TODO: Configure appropriate CellProcessors based on field types

            List<T> buffer = new ArrayList<>(BATCH_SIZE);

            importFlatProperty(repo, beanReader, processors, buffer, failedRecordHandler);

        } catch (Exception e) {
            throw new WebApplicationException("Error importing CSV: " + e.getMessage(), e, 400);
        }

        return new ImportResult<>(importedCount, failedCount);
    }

    private static <T extends BaseModel> ImportResult<T> importFlatProperty(BaseMorphiaRepo<T> repo,
                                                                     ICsvDozerBeanReader beanReader,
                                                                     CellProcessor[] processors,
                                                                     List<T> buffer,
                                                                     FailedRecordHandler<T> failedRecordHandler) throws Exception {
        int importedCount = 0;
        int failedCount = 0;

        while (true) {
            T bean = beanReader.read(repo.getPersistentClass(), processors);
            if (bean == null) break;

            buffer.add(bean);
            if (buffer.size() >= BATCH_SIZE) {
                ImportResult<T> batchResult = processBatch(repo, buffer, failedRecordHandler);
                importedCount += batchResult.getImportedCount();
                failedCount += batchResult.getFailedCount();
                buffer.clear();
            }
        }

        // Process any remaining entities in the buffer
        if (!buffer.isEmpty()) {
            ImportResult<T> batchResult = processBatch(repo, buffer, failedRecordHandler);
            importedCount += batchResult.getImportedCount();
            failedCount += batchResult.getFailedCount();
        }

        return new ImportResult<>(importedCount, failedCount);
    }

    private static <T extends BaseModel> ImportResult<T> processBatch(BaseMorphiaRepo<T> repo, 
                                                               List<T> batch, 
                                                               FailedRecordHandler<T> failedRecordHandler) {
        if (batch.isEmpty()) return new ImportResult<>(0, 0);

        int importedCount = 0;
        int failedCount = 0;

        try {
            repo.save(batch);
            importedCount = batch.size();
        } catch (Exception e) {
            if (batch.size() == 1) {
                failedRecordHandler.handleFailedRecord(batch.get(0));
                failedCount = 1;
            } else {
                int midPoint = batch.size() / 2;
                List<T> firstHalf = new ArrayList<>(batch.subList(0, midPoint));
                List<T> secondHalf = new ArrayList<>(batch.subList(midPoint, batch.size()));

                ImportResult<T> firstHalfResult = processBatch(repo, firstHalf, failedRecordHandler);
                ImportResult<T> secondHalfResult = processBatch(repo, secondHalf, failedRecordHandler);

                importedCount = firstHalfResult.getImportedCount() + secondHalfResult.getImportedCount();
                failedCount = firstHalfResult.getFailedCount() + secondHalfResult.getFailedCount();
            }
        }

        return new ImportResult<>(importedCount, failedCount);
    }
}
package com.e2eq.framework.util;


import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;

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
import java.util.Set;

import static java.lang.String.format;

@ApplicationScoped
public class CSVImportHelper {
    private static final int BATCH_SIZE = 1000;

    @Inject
    Validator validator;

    public <T> void validateBean(T bean) {
        Set<ConstraintViolation<T>> violations = validator.validate(bean);
        if (!violations.isEmpty()) {
            for (ConstraintViolation<T> violation : violations) {
                System.out.println("Validation error: " + violation.getMessage());
            }
            throw new ValidationException("Validation failed");
        }
    }

    private QuoteMode getQuoteMode(String quotingStrategy) {
        if ("QUOTE_ALL_COLUMNS".equalsIgnoreCase(quotingStrategy)) {
            return new AlwaysQuoteMode();
        } else if ("QUOTE_WHERE_ESSENTIAL".equalsIgnoreCase(quotingStrategy)) {
            return new NormalQuoteMode();
        } else {
            throw new ValidationException(format("The value %s is not one of the supported quote strategies: %s or %s",
                    quotingStrategy, "QUOTE_ALL_COLUMNS", "QUOTE_WHERE_ESSENTIAL"));
        }
    }

    private  String[] createFieldMapping(String[] header, List<String> requestedColumns, List<String> preferredColumnNames) {
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
        private int importedCount;
        private int failedCount;
        private final List<String> failedRecordsFeedback;

        public ImportResult(int importedCount, int failedCount) {
            this.importedCount = importedCount;
            this.failedCount = failedCount;
            this.failedRecordsFeedback = new ArrayList<>();
        }

        public int getImportedCount() {
            return importedCount;
        }

        public void incrementFailedCount() {
            incrementFailedCount(1);
        }

        public void incrementFailedCount(int amount) {
            this.failedCount = this.failedCount + amount;
        }

        public void incrementImportedCount() {
          incrementImportedCount(1);
        }

        public void incrementImportedCount(int amount) {
            this.importedCount = this.importedCount + amount;
        }

        public int getFailedCount() {
            return failedCount;
        }
        public List<String> getFailedRecordsFeedback() {
            return failedRecordsFeedback;
        }

        public void addFailedRecordFeedback(String feedback) {
            this.failedRecordsFeedback.add(feedback);
        }
    }

    public <T extends UnversionedBaseModel> ImportResult<T> importCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy,
            FailedRecordHandler<T> failedRecordHandler) {

        ImportResult<T> result = new ImportResult<>(0,0);
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

            //String[] fieldMapping = createFieldMapping(header, requestedColumns, preferredColumnNames);

            // convert requestedColumns to an array of strings
            String[] fieldMapping = requestedColumns.toArray(new String[requestedColumns.size()]);

            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            CellProcessor[] processors = new CellProcessor[fieldMapping.length];
            // TODO: Configure appropriate CellProcessors based on field types

           result = importFlatProperty(repo, beanReader, processors, failedRecordHandler);


        } catch (IOException e) {
            throw new WebApplicationException("Error importing CSV: " + e.getMessage(), e, 400);
        }

        return result;
    }

    private  <T extends UnversionedBaseModel> ImportResult<T> importFlatProperty(BaseMorphiaRepo<T> repo,
                                                                     ICsvDozerBeanReader beanReader,
                                                                     CellProcessor[] processors,
                                                                     FailedRecordHandler<T> failedRecordHandler) throws IOException {

        ImportResult<T> result = new ImportResult<>(0, 0);
        List<T> batch = new ArrayList<>(BATCH_SIZE);

        while (true) {
            T bean = beanReader.read(repo.getPersistentClass(), processors);
            if (bean == null) break;
            try {
                validateBean(bean);
            } catch (ValidationException e) {
                failedRecordHandler.handleFailedRecord(bean);
                result.incrementFailedCount();
                result.addFailedRecordFeedback("Validation failed for record: " + bean.toString() + " due to " + e.getMessage());
                continue;
            }

            batch.add(bean);
            if (batch.size() >= BATCH_SIZE) {
                processBatch(repo, batch, failedRecordHandler, result);
                batch.clear();
            }
        }

        // Process any remaining entities in the buffer
        if (!batch.isEmpty()) {
             result = processBatch(repo, batch, failedRecordHandler, result);
        }

        return result;
    }

    private <T extends UnversionedBaseModel> ImportResult<T> processBatch(BaseMorphiaRepo<T> repo,
                                                               List<T> batch,
                                                               FailedRecordHandler<T> failedRecordHandler,
                                                               ImportResult<T> result) {
        if (batch.isEmpty()) return result;

        try {
            repo.save(batch);
            result.incrementImportedCount(batch.size());
        } catch (Exception e) {
            if (batch.size() == 1) {
                T failedRecord = batch.get(0);
                failedRecordHandler.handleFailedRecord(failedRecord);
                result.incrementFailedCount();
                result.addFailedRecordFeedback("Failed to process record: " + failedRecord.toString() + " due to " + e.getMessage());
            } else {
                int midPoint = batch.size() / 2;
                List<T> firstHalf = new ArrayList<>(batch.subList(0, midPoint));
                List<T> secondHalf = new ArrayList<>(batch.subList(midPoint, batch.size()));

                processBatch(repo, firstHalf, failedRecordHandler, result);
                processBatch(repo, secondHalf, failedRecordHandler, result);
            }
        }

        return result;
    }
}
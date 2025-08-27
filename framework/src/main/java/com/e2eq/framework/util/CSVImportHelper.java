package com.e2eq.framework.util;


import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.ImportSessionRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;

import lombok.Getter;
import lombok.Setter;
import org.supercsv.cellprocessor.*;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.exception.SuperCsvException;
import org.supercsv.io.dozer.CsvDozerBeanReader;
import org.supercsv.io.dozer.ICsvDozerBeanReader;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.AlwaysQuoteMode;
import org.supercsv.quote.NormalQuoteMode;
import org.supercsv.quote.QuoteMode;
import org.supercsv.util.CsvContext;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.stream.Collectors;

import static java.lang.String.format;

@ApplicationScoped
public class CSVImportHelper {
    private static final int BATCH_SIZE = 1000;
    private static final int PREVIEW_LIMIT = 100;
    // Fallback in-memory session store for environments without CDI injection (e.g., unit tests)
    private static final java.util.Map<String, ImportSession<?>> MEMORY_SESSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    Validator validator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ImportSessionRepo importSessionRepo;

    @Inject
    com.e2eq.framework.model.persistent.morphia.ImportSessionRowRepo importSessionRowRepo;

    @Inject
    io.quarkus.security.identity.SecurityIdentity securityIdentity;

    public <T> void validateBean(T bean) {
        Set<ConstraintViolation<T>> violations = validator.validate(bean);

        if (!violations.isEmpty()) {

            // create a message from the violations:
            StringBuilder messageBuffer = new StringBuilder();
            for (ConstraintViolation<T> violation : violations) {
                messageBuffer.append(violation.getMessage()).append("\n");
            }

            throw new ValidationException(messageBuffer.toString().trim());
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

    private String[] createFieldMapping(String[] header, List<String> requestedColumns, List<String> preferredColumnNames) {
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

    @Getter
    public static class ImportResult<T> {
        // New: session and detailed metrics/results for preview and commit
        @Setter
        private String sessionId;
        private int totalRows;
        private int validRows;
        private int errorRows;
        @Setter
        private int updateCount;
        @Setter
        private int insertCount;
        private int importedCount;
        private int failedCount;
        private final List<String> failedRecordsFeedback;
        private final List<ImportRowResult<T>> rowResults = new ArrayList<>();

        public ImportResult(int importedCount, int failedCount) {
            this.importedCount = importedCount;
            this.failedCount = failedCount;
            this.failedRecordsFeedback = new ArrayList<>();
        }

        public void incrementTotalRows() {
            this.totalRows++;
        }

        public void incrementValidRows() {
            this.validRows++;
        }

        public void incrementErrorRows() {
            this.errorRows++;
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

        public void addFailedRecordFeedback(String feedback) {
            this.failedRecordsFeedback.add(feedback);
        }

    }

    public enum FieldErrorCode {
        VALIDATION,
        PARSE,
        MAPPING,
        DB,
        DB_DOC_TOO_LARGE,
        DB_SCHEMA_VALIDATION
    }

    // Per-field error information
    @Setter
    @Getter
    public static class FieldError {
        private String field; // may be null if unknown
        private String message;
        private FieldErrorCode code; // e.g., VALIDATION, PARSE, MAPPING, DB

        public FieldError() {
        }

        public FieldError(String field, String message, FieldErrorCode code) {
            this.field = field;
            this.message = message;
            this.code = code;
        }

    }

    public enum Intent {INSERT, UPDATE, SKIP}

    // Per-row result
    @Getter
    public static class ImportRowResult<T> {
        @Setter
        private int rowNumber;
        @Setter
        private String refName;
        @Setter
        private Intent intent = Intent.SKIP;
        @Setter
        private String rawData;
        private final List<FieldError> errors = new ArrayList<>();
        @Setter
        private T record; // optional reference for debugging

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    // Minimal session holder for preview/commit flow
    @Getter
    public static class ImportSession<T> {
        private final String id;
        private final Class<T> type;
        private final List<ImportRowResult<T>> rows;
        @Setter
        private String status; // PENDING, READY, COMMITTED, CANCELED

        public ImportSession(String id, Class<T> type, List<ImportRowResult<T>> rows) {
            this.id = id;
            this.type = type;
            this.rows = rows;
            this.status = "READY";
        }

    }

    @Getter
    public static class CommitResult {
        private final int imported;
        private final int failed;

        public CommitResult(int imported, int failed) {
            this.imported = imported;
            this.failed = failed;
        }
    }

    private static String safeRaw(org.supercsv.io.ICsvReader r) {
        try {
            return r.getUntokenizedRow();
        } catch (Exception ignore) {
            return null;
        }
    }

    public <T extends UnversionedBaseModel> ImportResult<T> preProcessCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy,
            FailedRecordHandler<T> failedRecordHandler
    ) throws IOException {
        ImportResult<T> result = new ImportResult<>(0, 0);
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

            ListCellProcessor listProcessor = new ListCellProcessor();
            CellProcessor[] processors = buildProcessors(repo.getPersistentClass(), requestedColumns, listProcessor);

            result = preprocessFlatProperty(repo, beanReader, processors, failedRecordHandler);


        } catch (IOException e) {
            throw new WebApplicationException("Error importing CSV: " + e.getMessage(), e, 400);
        }

        return result;
    }

    public static class ParseEnum<E extends Enum<E>> extends CellProcessorAdaptor {
        private final Class<E> enumType;

        public ParseEnum(Class<E> enumType) {
            this.enumType = enumType;
        }

        @Override
        public Object execute(Object value, CsvContext ctx) {
            if (value == null) return next.execute(null, ctx);
            String s = value.toString().trim();
            for (E e : enumType.getEnumConstants()) {
                if (e.name().equalsIgnoreCase(s)) {
                    return next.execute(e, ctx);
                }
            }
            throw new org.supercsv.exception.SuperCsvCellProcessorException(
                    "Invalid enum value '" + s + "' for " + enumType.getSimpleName(), ctx, this);
        }
    }

    private CellProcessor[] buildProcessors(Class<?> clazz, List<String> cols, ListCellProcessor listProcessor) {
        CellProcessor[] processors = new CellProcessor[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            String fieldName = cols.get(i);
            if (fieldName.contains("[")) {
                processors[i] = new org.supercsv.cellprocessor.Optional(listProcessor);
                continue;
            }
            Class<?> type = getFieldType(clazz, fieldName);
            if (type == int.class || type == Integer.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseInt());
            } else if (type == long.class || type == Long.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseLong());
            } else if (type == double.class || type == Double.class ||
                    type == float.class || type == Float.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseDouble());
            } else if (type == java.math.BigDecimal.class) {
                processors[i] = new org.supercsv.cellprocessor.Optional(new ParseBigDecimal());
            } else if (type != null && type.isEnum()) {
                @SuppressWarnings("unchecked")
                Class<? extends Enum<?>> e = (Class<? extends Enum<?>>) type;
                processors[i] = new Optional(new ParseEnum(e));  // << key change
            } else {
                processors[i] = new org.supercsv.cellprocessor.Optional();
            }
        }
        return processors;
    }

    private Class<?> getFieldType(Class<?> clazz, String name) {
        String clean = name.replace("[0]", "");
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(clean).getType();
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    public class ListCellProcessor extends CellProcessorAdaptor {

        protected int i;

        public Object execute(Object value, CsvContext context) {
            validateInputNotNull(value, context);

            if (value instanceof List) {
                List list = ((List) value);
                if (i >= list.size()) {
                    return next.execute("", context);
                } else {
                    return next.execute(((List) value).get(i), context);
                }

            } else {
                return next.execute(value, context);
            }
        }

        public void setIndex(int index) {
            this.i = index;
        }
    }

    private Reader makeReader(InputStream in, Charset cs, boolean mustUseBOM) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, cs));
        br.mark(1);
        int ch = br.read();
        if (!(mustUseBOM && ch == 0xFEFF)) br.reset(); // only consume if BOM was required and present
        return br;
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

        ImportResult<T> result = new ImportResult<>(0, 0);

        try (Reader reader = makeReader(inputStream, charset, mustUseBOM)) {
            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(getQuoteMode(quotingStrategy))
                            .build());

            String[] header = beanReader.getHeader(skipHeaderRow);
            if (skipHeaderRow && header == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            final String[] fieldMapping = requestedColumns.toArray(new String[0]);
            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            ListCellProcessor listProcessor = new ListCellProcessor();
            CellProcessor[] processors = buildProcessors(repo.getPersistentClass(), requestedColumns, listProcessor);

            result = importFlatProperty(repo, beanReader, processors, failedRecordHandler, fieldMapping);
        } catch (IOException e) {
            throw new WebApplicationException("Error importing CSV: " + e.getMessage(), e, 400);
        }

        return result;
    }


    private <T extends UnversionedBaseModel> ImportResult<T> preprocessFlatProperty(BaseMorphiaRepo<T> repo,
                                                                                    ICsvDozerBeanReader beanReader,
                                                                                    CellProcessor[] processors,
                                                                                    FailedRecordHandler<T> failedRecordHandler) throws IOException {
        ImportResult<T> result = new ImportResult<>(0, 0);
        List<T> batch = new ArrayList<>(BATCH_SIZE);

        while (true) {
            T bean = beanReader.read(repo.getPersistentClass(), processors);
            if (bean == null) break;

            batch.add(bean);
            if (batch.size() >= BATCH_SIZE) {
                preProcessBatch(repo, batch, result);
                batch.clear();
            }
        }

        // Process any remaining entities in the buffer
        if (!batch.isEmpty()) {
            result = preProcessBatch(repo, batch, result);
        }

        return result;
    }

    private static String resolveFieldFromParseException(Exception ex, String[] fieldMapping) {
        if (ex instanceof SuperCsvException sce) {
            CsvContext ctx = sce.getCsvContext();
            if (ctx != null) {
                int col = ctx.getColumnNumber(); // 1-based
                if (col > 0 && col <= fieldMapping.length) {
                    return fieldMapping[col - 1];
                }
            }
        }
        return null; // fallback if not available
    }

    private <T extends UnversionedBaseModel> ImportResult<T> importFlatProperty(
            BaseMorphiaRepo<T> repo,
            ICsvDozerBeanReader beanReader,
            CellProcessor[] processors,
            FailedRecordHandler<T> failedRecordHandler,
            String[] fieldMapping) throws IOException {

        ImportResult<T> result = new ImportResult<>(0, 0);
        List<T> batch = new ArrayList<>(BATCH_SIZE);

        // Track CSV row numbers and raw lines so we can annotate DB failures later
        Map<T, Integer> rowNums = new IdentityHashMap<>();
        Map<T, String> raws = new IdentityHashMap<>();

        int rowNum = 1; // first data row after header
        while (true) {
            T bean;
            try {
                bean = beanReader.read(repo.getPersistentClass(), processors);
            } catch (SuperCsvException ex) {
                String field = resolveFieldFromParseException(ex, fieldMapping);
                ImportRowResult<T> rr = new ImportRowResult<>();
                rr.setRowNumber(rowNum);
                rr.setIntent(Intent.SKIP);
                rr.setRawData(safeRaw(beanReader));
                rr.getErrors().add(new FieldError(field, ex.getMessage(), FieldErrorCode.PARSE));
                rowNum++;
                continue;
            } catch (Exception ex) {
                ImportRowResult<T> rr = new ImportRowResult<>();
                rr.setRowNumber(rowNum);
                rr.setIntent(Intent.SKIP);
                rr.setRawData(safeRaw(beanReader)); // NEW
                rr.getErrors().add(new FieldError(null, ex.getMessage(), FieldErrorCode.PARSE));

                result.incrementTotalRows();
                result.incrementErrorRows();
                result.incrementFailedCount();
                result.getRowResults().add(rr);

                rowNum++;
                continue;
            }

            if (bean == null) break; // EOF

            result.incrementTotalRows();

            // Validate (collect, don't throw)
            Set<jakarta.validation.ConstraintViolation<T>> violations = validator.validate(bean);
            if (violations != null && !violations.isEmpty()) {
                ImportRowResult<T> rr = new ImportRowResult<>();
                rr.setRowNumber(rowNum);
                rr.setRefName(bean.getRefName());
                rr.setRawData(safeRaw(beanReader)); // NEW

                for (jakarta.validation.ConstraintViolation<T> v : violations) {
                    rr.getErrors().add(new FieldError(
                            v.getPropertyPath() != null ? v.getPropertyPath().toString() : null,
                            v.getMessage(),
                            FieldErrorCode.VALIDATION
                    ));
                }
                if (failedRecordHandler != null) failedRecordHandler.handleFailedRecord(bean);
                result.incrementFailedCount();
                result.incrementErrorRows();

                result.getRowResults().add(rr);


                rowNum++;
                continue; // skip DB save
            }

            // Valid row (pre-DB)
            result.incrementValidRows();

            // Buffer for DB save + keep pointers for error reporting
            batch.add(bean);
            rowNums.put(bean, rowNum);
            raws.put(bean, safeRaw(beanReader));

            if (batch.size() >= BATCH_SIZE) {
                processBatch(repo, batch, failedRecordHandler, result, rowNums, raws);
                batch.clear();
                rowNums.clear();
                raws.clear();
            }

            rowNum++;
        }

        // Flush remaining
        if (!batch.isEmpty()) {
            result = processBatch(repo, batch, failedRecordHandler, result, rowNums, raws);
            batch.clear();
            rowNums.clear();
            raws.clear();
        }

        return result;
    }

    public <T extends UnversionedBaseModel> ImportResult<T> preProcessBatch(
            BaseMorphiaRepo<T> repo,
            List<T> batch,
            ImportResult<T> result) {
        if (batch.isEmpty()) return result;

        // gather a list of refNames from the batch
        List<String> refNames = batch.stream().map(T::getRefName).toList();

        // determine which records are updates and which ones are inserts
        List<T> foundEntities = repo.getListFromRefNames(refNames);
        List<String> foundRefNames = foundEntities.stream().map(T::getRefName).toList();

        List<T> newEntities = batch.stream().filter(entity -> !foundRefNames.contains(entity.getRefName())).toList();
        List<T> existingEntities = batch.stream().filter(entity -> foundRefNames.contains(entity.getRefName())).toList();

        int validNew = 0;
        for (T model : newEntities) {
            try {
                validateBean(model);
                validNew++;
            } catch (ValidationException e) {
                result.incrementFailedCount();
                result.addFailedRecordFeedback("Validation failed for record: " + model.toString() + " due to " + e.getMessage());
            }
        }

        int validExisting = 0;
        for (T model : existingEntities) {
            try {
                validateBean(model);
                validExisting++;
            } catch (ValidationException e) {
                result.incrementFailedCount();
                result.addFailedRecordFeedback("Validation failed for record: " + model.toString() + " due to " + e.getMessage());
            }
        }

        // accumulate counts
        result.setInsertCount(result.getInsertCount() + validNew);
        result.setUpdateCount(result.getUpdateCount() + validExisting);

        return result;
    }

    private <T extends UnversionedBaseModel> ImportResult<T> processBatch(
            BaseMorphiaRepo<T> repo,
            List<T> batch,
            FailedRecordHandler<T> failedRecordHandler,
            ImportResult<T> result,
            Map<T, Integer> rowNums,
            Map<T, String> raws) {

        if (batch.isEmpty()) return result;

        // Determine intended INSERT vs UPDATE counts for this batch (used on success)
        List<String> refNames = batch.stream().map(T::getRefName).toList();
        List<T> found = repo.getListFromRefNames(refNames);
        Set<String> existing = found.stream()
                .map(T::getRefName)
                .collect(java.util.stream.Collectors.toSet());

        int batchInserts = 0, batchUpdates = 0;
        for (T b : batch) {
            if (existing.contains(b.getRefName())) batchUpdates++;
            else batchInserts++;
        }

        try {
            // Attempt save
            repo.save(batch);

            // On success, reflect counts
            result.incrementImportedCount(batch.size());
            result.setInsertCount(result.getInsertCount() + batchInserts);
            result.setUpdateCount(result.getUpdateCount() + batchUpdates);

        } catch (Exception e) {
            if (batch.size() == 1) {
                // Single-record failure: attach structured DB error
                T failedRecord = batch.get(0);

                ImportRowResult<T> rr = new ImportRowResult<>();
                rr.setRowNumber(rowNums.getOrDefault(failedRecord, -1));
                rr.setRefName(failedRecord.getRefName());
                rr.setRawData(raws.get(failedRecord)); // NEW

                // Classify DB error (duplicate key, etc.)
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();

                FieldErrorCode code = FieldErrorCode.DB;
                String message = root.getMessage();
                if (message != null) {
                    if (message.contains("E11000")) {
                        message = "Duplicate key violation (unique index).";
                    } else if (message.contains("Document too large")) {
                        code = FieldErrorCode.DB_DOC_TOO_LARGE;
                    } else if (message.contains("Validation failed")) {
                        code = FieldErrorCode.DB_SCHEMA_VALIDATION;
                    }
                }

                rr.getErrors().add(new FieldError(
                        "refName", // set null if unknown
                        message != null ? message : "Database write failed.",
                        code
                ));

                if (failedRecordHandler != null) failedRecordHandler.handleFailedRecord(failedRecord);
                result.incrementFailedCount();
                result.incrementErrorRows();

                result.getRowResults().add(rr);


            } else {
                // Bisect to isolate failing records without losing successes
                int midPoint = batch.size() / 2;
                List<T> firstHalf = new ArrayList<>(batch.subList(0, midPoint));
                List<T> secondHalf = new ArrayList<>(batch.subList(midPoint, batch.size()));

                processBatch(repo, firstHalf, failedRecordHandler, result, rowNums, raws);
                processBatch(repo, secondHalf, failedRecordHandler, result, rowNums, raws);
            }
        }

        return result;
    }

    // Backward-compatible delegator (kept)
    private <T extends UnversionedBaseModel> ImportResult<T> processBatch(
            BaseMorphiaRepo<T> repo,
            List<T> batch,
            FailedRecordHandler<T> failedRecordHandler,
            ImportResult<T> result) {
        return processBatch(repo, batch, failedRecordHandler, result,
                java.util.Collections.emptyMap(), java.util.Collections.emptyMap());
    }

    // New: analyze (preview) the CSV without saving, and create a session
    public <T extends UnversionedBaseModel> ImportResult<T> analyzeCSV(
            BaseMorphiaRepo<T> repo,
            InputStream inputStream,
            char fieldSeparator,
            char quoteChar,
            boolean skipHeaderRow,
            List<String> requestedColumns,
            Charset charset,
            boolean mustUseBOM,
            String quotingStrategy) throws IOException {

        ImportResult<T> result = new ImportResult<>(0, 0);

        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        List<ImportRowResult<T>> memRows = memMode ? new ArrayList<>() : null;

        String sessionId = java.util.UUID.randomUUID().toString();
        result.setSessionId(sessionId);

        com.e2eq.framework.model.persistent.imports.ImportSession session = null;
        if (!memMode) {
            session = new com.e2eq.framework.model.persistent.imports.ImportSession();
            session.setRefName(sessionId);
            session.setDisplayName("Import Session " + sessionId);
            session.setTargetType(repo.getPersistentClass().getName());
            session.setStatus("OPEN");
            String currentUser = null;
            try {
                if (securityIdentity != null && securityIdentity.getPrincipal() != null) {
                    currentUser = securityIdentity.getPrincipal().getName();
                }
            } catch (Exception ignored) {
            }
            if (currentUser == null || currentUser.isEmpty()) currentUser = "anonymous";
            session.setUserId(currentUser);
            session.setCollectionName(repo.getPersistentClass().getSimpleName());
            session.setStartedAt(java.time.Instant.now());
            importSessionRepo.save(session);
        }

        try (Reader reader = makeReader(inputStream, charset, mustUseBOM)) {
            ICsvDozerBeanReader beanReader = new CsvDozerBeanReader(reader,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(getQuoteMode(quotingStrategy))
                            .build());

            String[] header = beanReader.getHeader(skipHeaderRow);
            if (skipHeaderRow && header == null) {
                throw new IllegalArgumentException("CSV file does not contain a header row");
            }

            final String[] fieldMapping = requestedColumns.toArray(new String[0]);
            beanReader.configureBeanMapping(repo.getPersistentClass(), fieldMapping);

            ListCellProcessor listProcessor = new ListCellProcessor();
            CellProcessor[] processors = buildProcessors(repo.getPersistentClass(), requestedColumns, listProcessor);

            List<T> batchBeans = new ArrayList<>(BATCH_SIZE);
            List<Integer> batchRowNums = new ArrayList<>(BATCH_SIZE);
            Map<Integer, String> batchRawByRowNum = new java.util.HashMap<>(BATCH_SIZE);

            int rowNum = 1; // first data row after (optional) header
            while (true) {
                T bean;
                try {
                    bean = beanReader.read(repo.getPersistentClass(), processors);
                } catch (SuperCsvException ex) {
                    String field = resolveFieldFromParseException(ex, fieldMapping);

                    ImportRowResult<T> rr = new ImportRowResult<>();
                    rr.setRowNumber(rowNum);
                    rr.setIntent(Intent.SKIP);
                    rr.setRawData(safeRaw(beanReader));
                    rr.getErrors().add(new FieldError(field, ex.getMessage(), FieldErrorCode.PARSE));

                    if (!memMode) {
                        com.e2eq.framework.model.persistent.imports.ImportSessionRow row =
                                new com.e2eq.framework.model.persistent.imports.ImportSessionRow();
                        row.setSessionRefName(sessionId);
                        row.setRowNumber(rowNum);
                        row.setRefName(null);
                        row.setIntent(Intent.SKIP.name());
                        row.setHasErrors(true);
                        row.setRawLine(rr.getRawData()); // use captured raw
                        try {
                            row.setErrorsJson(objectMapper.writeValueAsString(rr.getErrors()));
                        } catch (Exception ignore) {
                        }
                        importSessionRowRepo.save(row);
                    } else {
                        memRows.add(rr);
                    }

                    result.incrementTotalRows();
                    result.incrementErrorRows();
                    if (result.getRowResults().size() < PREVIEW_LIMIT) {
                        result.getRowResults().add(rr);
                    }

                    rowNum++;
                    continue;
                } catch (Exception ex) {
                    ImportRowResult<T> rr = new ImportRowResult<>();
                    rr.setRowNumber(rowNum);
                    rr.setIntent(Intent.SKIP);
                    rr.setRawData(safeRaw(beanReader));
                    rr.getErrors().add(new FieldError(null, ex.getMessage(), FieldErrorCode.PARSE));

                    if (!memMode) {
                        com.e2eq.framework.model.persistent.imports.ImportSessionRow row =
                                new com.e2eq.framework.model.persistent.imports.ImportSessionRow();
                        row.setSessionRefName(sessionId);
                        row.setRowNumber(rowNum);
                        row.setRefName(null);
                        row.setIntent(Intent.SKIP.name());
                        row.setHasErrors(true);
                        row.setRawLine(rr.getRawData()); // use captured raw
                        try {
                            row.setErrorsJson(objectMapper.writeValueAsString(rr.getErrors()));
                        } catch (Exception ignore) {
                        }
                        importSessionRowRepo.save(row);
                    } else {
                        memRows.add(rr);
                    }

                    result.incrementTotalRows();
                    result.incrementErrorRows();
                    if (result.getRowResults().size() < PREVIEW_LIMIT) {
                        result.getRowResults().add(rr);
                    }

                    rowNum++;
                    continue;
                }

                if (bean == null) break; // EOF

                ImportRowResult<T> rr = new ImportRowResult<>();
                rr.setRowNumber(rowNum);
                rr.setRefName(bean.getRefName());
                rr.setRecord(bean);

                // capture raw for this row (to be attached during validation)
                batchRawByRowNum.put(rowNum, safeRaw(beanReader));
                batchBeans.add(bean);
                batchRowNums.add(rowNum);

                if (batchBeans.size() >= BATCH_SIZE) {
                    List<ImportRowResult<T>> annotated = new ArrayList<>();
                    // >>> PASS raw map
                    annotateIntentsAndValidate(repo, batchBeans, batchRowNums, batchRawByRowNum, annotated, result);

                    for (ImportRowResult<T> ar : annotated) {
                        result.incrementTotalRows();

                        boolean hasErrors = ar.hasErrors();
                        if (hasErrors) {
                            result.incrementErrorRows();
                        } else {
                            result.incrementValidRows();
                            if (ar.getIntent() == Intent.INSERT) {
                                result.setInsertCount(result.getInsertCount() + 1);
                            } else if (ar.getIntent() == Intent.UPDATE) {
                                result.setUpdateCount(result.getUpdateCount() + 1);
                            }
                        }

                        if (result.getRowResults().size() < PREVIEW_LIMIT) {
                            result.getRowResults().add(ar);
                        }

                        if (!memMode) {
                            com.e2eq.framework.model.persistent.imports.ImportSessionRow row =
                                    new com.e2eq.framework.model.persistent.imports.ImportSessionRow();
                            row.setSessionRefName(sessionId);
                            row.setRowNumber(ar.getRowNumber());
                            row.setRefName(ar.getRefName());
                            row.setIntent(ar.getIntent().name());
                            row.setHasErrors(hasErrors);
                            // use the captured raw for the specific row
                            String raw = batchRawByRowNum.get(ar.getRowNumber());
                            if (raw == null) raw = ar.getRawData();
                            row.setRawLine(raw);
                            try {
                                row.setErrorsJson(objectMapper.writeValueAsString(ar.getErrors()));
                                row.setRecordJson(objectMapper.writeValueAsString(ar.getRecord()));
                            } catch (Exception ignore) {
                            }
                            importSessionRowRepo.save(row);
                        } else {
                            memRows.add(ar);
                        }
                    }

                    batchBeans.clear();
                    batchRowNums.clear();
                    batchRawByRowNum.clear();
                }

                rowNum++;
            }

            if (!batchBeans.isEmpty()) {
                List<ImportRowResult<T>> annotated = new ArrayList<>();
                // >>> PASS raw map for final flush
                annotateIntentsAndValidate(repo, batchBeans, batchRowNums, batchRawByRowNum, annotated, result);

                for (ImportRowResult<T> ar : annotated) {
                    result.incrementTotalRows();

                    boolean hasErrors = ar.hasErrors();
                    if (hasErrors) {
                        result.incrementErrorRows();
                    } else {
                        result.incrementValidRows();
                        if (ar.getIntent() == Intent.INSERT) {
                            result.setInsertCount(result.getInsertCount() + 1);
                        } else if (ar.getIntent() == Intent.UPDATE) {
                            result.setUpdateCount(result.getUpdateCount() + 1);
                        }
                    }

                    if (result.getRowResults().size() < PREVIEW_LIMIT) {
                        result.getRowResults().add(ar);
                    }

                    if (!memMode) {
                        com.e2eq.framework.model.persistent.imports.ImportSessionRow row =
                                new com.e2eq.framework.model.persistent.imports.ImportSessionRow();
                        row.setSessionRefName(sessionId);
                        row.setRowNumber(ar.getRowNumber());
                        row.setRefName(ar.getRefName());
                        row.setIntent(ar.getIntent().name());
                        row.setHasErrors(hasErrors);
                        // use the captured raw for the specific row
                        String raw = batchRawByRowNum.get(ar.getRowNumber());
                        if (raw == null) raw = ar.getRawData();
                        row.setRawLine(raw);
                        try {
                            row.setErrorsJson(objectMapper.writeValueAsString(ar.getErrors()));
                            row.setRecordJson(objectMapper.writeValueAsString(ar.getRecord()));
                        } catch (Exception ignore) {
                        }
                        importSessionRowRepo.save(row);
                    } else {
                        memRows.add(ar);
                    }
                }
            }
        } catch (IOException e) {
            throw new WebApplicationException("Error analyzing CSV: " + e.getMessage(), e, 400);
        }

        if (!memMode) {
            session.setTotalRows(result.getTotalRows());
            session.setValidRows(result.getValidRows());
            session.setErrorRows(result.getErrorRows());
            session.setInsertCount(result.getInsertCount());
            session.setUpdateCount(result.getUpdateCount());
            importSessionRepo.save(session);
        } else {
            MEMORY_SESSIONS.put(sessionId, new ImportSession<>(sessionId, repo.getPersistentClass(), memRows));
        }

        return result;
    }


    private <T extends UnversionedBaseModel> void annotateIntentsAndValidate(
            BaseMorphiaRepo<T> repo,
            List<T> batchBeans,
            List<Integer> batchRowNums,
            Map<Integer, String> rawByRowNum,          // <<< added
            List<ImportRowResult<T>> outRows,
            ImportResult<T> aggregate) {

        List<String> refNames = batchBeans.stream().map(T::getRefName).toList();
        List<T> found = repo.getListFromRefNames(refNames);
        java.util.Set<String> existing = found.stream()
                .map(T::getRefName)
                .collect(java.util.stream.Collectors.toSet());

        for (int i = 0; i < batchBeans.size(); i++) {
            T bean = batchBeans.get(i);
            int rn = batchRowNums.get(i);

            ImportRowResult<T> rr = new ImportRowResult<>();
            rr.setRowNumber(rn);
            rr.setRefName(bean.getRefName());
            rr.setRecord(bean);
            rr.setIntent(existing.contains(bean.getRefName()) ? Intent.UPDATE : Intent.INSERT);

            // attach captured raw line (always; or gate this on errors if desired)
            String raw = rawByRowNum != null ? rawByRowNum.get(rn) : null;
            if (raw != null) {
                rr.setRawData(raw);
            }

            // validate
            try {
                Set<ConstraintViolation<T>> violations = validator.validate(bean);
                if (violations != null) {
                    for (ConstraintViolation<T> v : violations) {
                        rr.getErrors().add(new FieldError(
                                v.getPropertyPath() != null ? v.getPropertyPath().toString() : null,
                                v.getMessage(),
                                FieldErrorCode.VALIDATION));
                    }
                }
            } catch (Exception ex) {
                rr.getErrors().add(new FieldError(null, ex.getMessage(), FieldErrorCode.VALIDATION));
            }

            outRows.add(rr);
        }
    }


    // Minimal commit: save all valid rows for INSERT/UPDATE from a session
    @SuppressWarnings("unchecked")
    public <T extends UnversionedBaseModel> CommitResult commitImport(String sessionId, BaseMorphiaRepo<T> repo) {
        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        if (memMode) {
            ImportSession<T> session = (ImportSession<T>) MEMORY_SESSIONS.get(sessionId);
            if (session == null) {
                throw new ValidationException("Unknown import session: " + sessionId);
            }
            if ("COMPLETED".equalsIgnoreCase(session.getStatus()) || "COMMITTED".equalsIgnoreCase(session.getStatus())) {
                return new CommitResult(0, 0);
            }
            List<T> toSave = new ArrayList<>();
            for (ImportRowResult<T> rr : session.getRows()) {
                if (!rr.hasErrors() && (rr.getIntent() == Intent.INSERT || rr.getIntent() == Intent.UPDATE)) {
                    toSave.add(rr.getRecord());
                }
            }
            ImportResult<T> tmp = new ImportResult<>(0, 0);
            processBatch(repo, toSave, rec -> {
            }, tmp);
            session.setStatus("COMMITTED");
            return new CommitResult(tmp.getImportedCount(), tmp.getFailedCount());
        }
        com.e2eq.framework.model.persistent.imports.ImportSession session = importSessionRepo.findByRefName(sessionId)
                .orElseThrow(() -> new ValidationException("Unknown import session: " + sessionId));
        String st = session.getStatus();
        if ("COMPLETED".equalsIgnoreCase(st) || "COMMITTED".equalsIgnoreCase(st)) {
            return new CommitResult(0, 0);
        }
        if (!repo.getPersistentClass().getName().equals(session.getTargetType())) {
            throw new ValidationException(
                    "Session target type does not match repository persistent class"
            );
        }
        ImportResult<T> tmp = new ImportResult<>(0, 0);
        int skip = 0;
        int pageSize = BATCH_SIZE;
        while (true) {
            java.util.List<dev.morphia.query.filters.Filter> filters = new java.util.ArrayList<>();
            filters.add(dev.morphia.query.filters.Filters.eq("sessionRefName", sessionId));
            filters.add(dev.morphia.query.filters.Filters.eq("hasErrors", false));
            filters.add(dev.morphia.query.filters.Filters.in("intent", java.util.List.of(Intent.INSERT.name(), Intent.UPDATE.name())));
            java.util.List<com.e2eq.framework.model.persistent.imports.ImportSessionRow> page = importSessionRowRepo.getList(skip, pageSize, filters, null);
            if (page == null || page.isEmpty()) break;
            List<T> toSave = new ArrayList<>(page.size());
            for (com.e2eq.framework.model.persistent.imports.ImportSessionRow r : page) {
                if (r.getRecordJson() == null) continue;
                try {
                    T bean = objectMapper.readValue(r.getRecordJson(), repo.getPersistentClass());
                    toSave.add(bean);
                } catch (Exception ex) {
                    // treat as failed row; skip
                }
            }
            if (!toSave.isEmpty()) {
                processBatch(repo, toSave, rec -> {
                }, tmp);
            }
            skip += page.size();
        }
        session.setStatus("COMPLETED");
        importSessionRepo.save(session);
        return new CommitResult(tmp.getImportedCount(), tmp.getFailedCount());
    }

    public void cancelImport(String sessionId) {
        boolean memMode = (importSessionRepo == null || importSessionRowRepo == null);
        if (memMode) {
            ImportSession<?> s = MEMORY_SESSIONS.remove(sessionId);
            if (s != null) {
                s.setStatus("CANCELED");
            }
            return;
        }
        importSessionRepo.findByRefName(sessionId).ifPresent(s -> {
            s.setStatus("CANCELLED");
            importSessionRepo.save(s);
        });
        // delete per-row data for this session to free storage
        int skip = 0;
        int pageSize = BATCH_SIZE;
        while (true) {
            java.util.List<dev.morphia.query.filters.Filter> filters = new java.util.ArrayList<>();
            filters.add(dev.morphia.query.filters.Filters.eq("sessionRefName", sessionId));
            java.util.List<com.e2eq.framework.model.persistent.imports.ImportSessionRow> page = importSessionRowRepo.getList(skip, pageSize, filters, null);
            if (page == null || page.isEmpty()) break;
            for (com.e2eq.framework.model.persistent.imports.ImportSessionRow r : page) {
                try {
                    importSessionRowRepo.delete(r);
                } catch (Exception ignore) {
                }
            }
            // do not increase skip since we delete as we go; always fetch from 0 until empty
            skip = 0;
        }
    }
}

package com.e2eq.framework.api.csv;


import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.persistent.TestCSVModelRepo;
import com.e2eq.framework.test.CSVModel;
import com.e2eq.framework.util.CSVExportHelper;
import com.e2eq.framework.util.CSVImportHelper;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.StreamingOutput;
import org.apache.commons.beanutils.PropertyUtils;
import org.junit.jupiter.api.Test;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.Optional;
import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.ParseInt;
import org.supercsv.cellprocessor.ParseBigDecimal;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.dozer.CsvDozerBeanReader;
import org.supercsv.io.dozer.CsvDozerBeanWriter;
import org.supercsv.io.dozer.ICsvDozerBeanReader;
import org.supercsv.io.dozer.ICsvDozerBeanWriter;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.NormalQuoteMode;
import org.supercsv.quote.QuoteMode;
import org.supercsv.util.CsvContext;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

@QuarkusTest
public class TestCSVFeatures extends BaseRepoTest {
    @Inject
    TestCSVModelRepo repo;

    @Inject
    CSVImportHelper csvImportHelper;

    private List<CSVModel> getRecords() {
        // Generate a list of TestCSVModels

        return List.of(
                CSVModel.builder()
                        .testField1("value1")
                        .testList(List.of("value2", "value3"))
                        .displayName("testDisplayName")
                        .numberField(1)
                        .decimalField(new java.math.BigDecimal("10.5"))
                        .build(),
                CSVModel.builder()
                        .testField1("value4")
                        .testList(List.of("value5", "value6"))
                        .displayName("testDisplayName2")
                        .numberField(2)
                        .decimalField(new java.math.BigDecimal("20.5"))
                        .build()
        );
    }

    private <T> void writeRecord(ICsvDozerBeanWriter beanWriter, T record, CellProcessor[] processors, String nestedProperty)
            throws IOException, ReflectiveOperationException {
        if (nestedProperty != null) {
            List<?> propertyValue = (List<?>) PropertyUtils.getProperty(record, nestedProperty);
            if (propertyValue == null || propertyValue.isEmpty()) {
                beanWriter.write(record, processors);
            } else {
                for (Object item : propertyValue) {
                    PropertyUtils.setProperty(record, nestedProperty, Arrays.asList(item));
                    beanWriter.write(record, processors);
                }
            }
        } else {
            beanWriter.write(record, processors);
        }
    }

    private void writeErrorToCSV(Writer writer, int status, Exception e) throws IOException {
        writer.write(status == 400
                ? "Incorrect information supplied: " + e.getMessage()
                : "Server failed to process request: " + e.getMessage());
    }


    @Test
    public void testSuperExportCSV()  {
        /**
         * Inner class to process CSV cell
         */
        class ListCellProcessor extends CellProcessorAdaptor {

            /**
             * Index of an item in a list being processed by processor
             */
            protected int i;

            /**
             * executes the cell processor for each cell
             *
             * @param value   value of the cell
             * @param context CsvContext
             * @return Object returns the processed cell value
             */
            public Object execute(Object value, CsvContext context) {

                validateInputNotNull(value, context);  // throws an Exception if the input is null

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
        }

        Class<?> clazz = CSVModel.class;
        Collection<CSVModel> records = getRecords();
        final char quoteChar = '"';
        final char fieldSeparator = ',';
        OutputStream output = System.out;
        Charset chosenCharset = Charset.forName("UTF-8");
        Map<String, String> headerToFieldMap = Map.of(
                "f1", "testField1",
                "a1", "testList",
                "ds", "displayName",
                "num", "numberField",
                "dec", "decimalField"
        );
        String nestedProperty = null;

        try (Writer writer = new OutputStreamWriter(output, chosenCharset)) {
            QuoteMode qmode = new NormalQuoteMode();

            try (ICsvDozerBeanWriter beanWriter = new CsvDozerBeanWriter(writer,
                    new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                            .useQuoteMode(qmode)
                            .build())) {

                String[] headers = headerToFieldMap.keySet().toArray(new String[0]);
                String[] fields = headerToFieldMap.values().toArray(new String[0]);

                // Configure bean mapping
                beanWriter.configureBeanMapping(clazz, fields);
                beanWriter.writeHeader(headers);


                CellProcessor[] processors = new CellProcessor[fields.length];
                ListCellProcessor listProcessor = new ListCellProcessor();
                Arrays.fill(processors, new org.supercsv.cellprocessor.Optional(listProcessor));
                for (CSVModel record : records) {
                    writeRecord(beanWriter, record, processors, nestedProperty);
                }
            } catch (Throwable e) {
                Log.error("Exception occurred", e);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @Test
    public void testCSVExportHelper() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        StreamingOutput streamingOutput = helper.streamCSVOut(
                CSVModel.class,
                getRecords(),
                null,
                ',',
                List.of("testField1", "testList", "displayName", "numberField", "decimalField"),
                "QUOTE_ALL_COLUMNS",
                '"',
                Charset.forName("UTF-8"),
                true,
                true,
                List.of("a1","a2","a3","a4","a5"),
                null);

        streamingOutput.write(System.out);
    }

    @Test
    public void testSuperCSVImport() throws IOException {
        final String[] FIELD_MAPPING = {"refName", "displayName", "testField", "testField2", "testField3", "testList[0]", "numberField", "decimalField"};
        final String[] PREFERRED_FIELD_MAPPING = {"ID", "NAME", "testField", "testField2", "testField3", "testListField1", "numberField", "decimalField"};

        final CellProcessor[] processors = new CellProcessor[]{
          new NotNull(),
          new NotNull(),
          new NotNull(),
          new Optional(),
          new Optional(),
          new Optional(),
          new ParseInt(),
          new ParseBigDecimal()
        };

        ICsvDozerBeanReader beanReader=null;
        try {
            beanReader = new CsvDozerBeanReader(
                    new InputStreamReader(getClass().getClassLoader().getResourceAsStream("testData/TestImportCSV.csv")),
                    CsvPreference.STANDARD_PREFERENCE);
            beanReader.getHeader(true);
            beanReader.configureBeanMapping(CSVModel.class, FIELD_MAPPING);

            CSVModel model;
            while ((model = beanReader.read(CSVModel.class, processors)) != null) {
                System.out.println(String.format("lineNo=%s, rowNo=%s, model=%s", beanReader.getLineNumber(),
                        beanReader.getRowNumber(), model));
            }
        }
        finally {
           if ( beanReader != null ) {
               beanReader.close();
           }
        }

    }

    @Test
    public void testCSVImportPreProcessor() throws IOException {
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            CSVImportHelper helper = csvImportHelper;
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("testData/TestImportCSV.csv");
            Charset chosenCharset = Charset.forName("UTF-8");
            List failedRecords = new ArrayList<>();
            CSVImportHelper.FailedRecordHandler failedRecordHandler = failedRecords::add;
            CSVImportHelper.ImportResult result = helper.preProcessCSV(
               repo,
               inputStream,
               ',',
               '"',
               false,
               List.of("refName", "displayName", "testField", "testField2", "testField3", "testList[0]", "numberField", "decimalField"),
               chosenCharset,
               false,
               "QUOTE_WHERE_ESSENTIAL",
               failedRecordHandler            );

            System.out.println("Preprocess Result: Update Count:" + result.getUpdateCount());
            System.out.println("Preprocess Result: Insert Count:" + result.getInsertCount());
            System.out.println("Preprocess Result: Imported Failed Count:" + result.getFailedCount());
            for (Object r : result.getFailedRecordsFeedback()) {
                System.out.println(r);
            }
        }
    }

    @Test
    public void testCSVImportHelper() throws IOException {

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            CSVImportHelper helper = csvImportHelper;
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("testData/TestImportCSV.csv");
            Charset chosenCharset = Charset.forName("UTF-8");
            List failedRecords = new ArrayList<>();
            CSVImportHelper.FailedRecordHandler failedRecordHandler = failedRecords::add;

            CSVImportHelper.ImportResult result = helper.importCSV(
                    repo,
                    inputStream,
                    ',',
                    '"',
                    false,
                    List.of("refName", "displayName", "testField", "testField2", "testField3", "testList[0]", "numberField", "decimalField"),
                    chosenCharset,
                    false,
                    "QUOTE_WHERE_ESSENTIAL",
                    failedRecordHandler);

            System.out.println("Import Result: Imported Count:" + result.getImportedCount());
            System.out.println("Import Result: Imported Failed Count:" + result.getFailedCount());
            for (Object r : result.getFailedRecordsFeedback()) {
                System.out.println(r);
            }
        }
    }
}

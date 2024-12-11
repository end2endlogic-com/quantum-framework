package com.e2eq.framework.api.csv;

import com.e2eq.framework.persistent.TestParentRepo;
import com.e2eq.framework.test.TestCSVModel;
import com.e2eq.framework.util.CSVExportHelper;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.StreamingOutput;
import org.apache.commons.beanutils.PropertyUtils;
import org.junit.jupiter.api.Test;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.dozer.CsvDozerBeanWriter;
import org.supercsv.io.dozer.ICsvDozerBeanWriter;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.AlwaysQuoteMode;
import org.supercsv.quote.NormalQuoteMode;
import org.supercsv.quote.QuoteMode;
import org.supercsv.util.CsvContext;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@QuarkusTest
public class TestCSVFeatures {
    @Inject
    TestParentRepo repo;

    private List<TestCSVModel> getRecords() {
        // Generate a list of TestCSVModels

        return List.of(
                TestCSVModel.builder()
                        .testField1("value1")
                        .testList(List.of("value2", "value3"))
                        .displayName("testDisplayName")
                        .build(),
                TestCSVModel.builder()
                        .testField1("value4")
                        .testList(List.of("value5", "value6"))
                        .displayName("testDisplayName2")
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
    public void testSuperCSV()  {
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

        Class clazz = TestCSVModel.class;
        Collection<TestCSVModel> records = getRecords();
        final char quoteChar = '"';
        final char fieldSeparator = ',';
        OutputStream output = System.out;
        Charset chosenCharset = Charset.forName("UTF-8");
        Map<String, String> headerToFieldMap = Map.of(
                "f1", "testField1",
                "a1", "testList",
                "ds", "displayName"
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
                for (TestCSVModel record : records) {
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
                TestCSVModel.class,
                getRecords(),
                null,
                ',',
                List.of("testField1", "testList", "displayName"),
                "QUOTE_ALL_COLUMNS",
                '"',
                Charset.forName("UTF-8"),
                true,
                true,
                List.of("a1","a2","a3"),
                null);

        streamingOutput.write(System.out);
    }
}

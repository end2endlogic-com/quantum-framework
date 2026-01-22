package com.e2eq.framework.api.csv;


import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.persistent.TestCSVModelRepo;
import com.e2eq.framework.persistent.TestParentRepo;
import com.e2eq.framework.test.CSVModel;
import com.e2eq.framework.test.ParentModel;
import com.e2eq.framework.util.CSVExportHelper;
import com.e2eq.framework.util.CSVImportHelper;

import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.StreamingOutput;
import org.apache.commons.beanutils.PropertyUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
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
    TestParentRepo parentRepo;

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

    /**
     * Creates test ParentModel records with nested dynamicAttributeSets for testing nested array export
     */
    private List<ParentModel> getParentModelRecords() {
        List<ParentModel> records = new ArrayList<>();

        // First record with nested arrays
        ParentModel model1 = new ParentModel();
        model1.setTestField("testValue1");
        model1.setRefName("parent1");

        DynamicAttributeSet set1 = new DynamicAttributeSet();
        set1.setName("logistics");
        List<DynamicAttribute> attrs1 = new ArrayList<>();
        DynamicAttribute attr1 = new DynamicAttribute();
        attr1.setName("shippingNumber");
        attr1.setValue("SHIP-001");
        attrs1.add(attr1);
        DynamicAttribute attr2 = new DynamicAttribute();
        attr2.setName("vatNumber");
        attr2.setValue("VAT-001");
        attrs1.add(attr2);
        DynamicAttribute attr3 = new DynamicAttribute();
        attr3.setName("containerNumber");
        attr3.setValue("CONT-001");
        attrs1.add(attr3);
        set1.setAttributes(attrs1);

        DynamicAttributeSet set2 = new DynamicAttributeSet();
        set2.setName("compliance");
        List<DynamicAttribute> attrs2 = new ArrayList<>();
        DynamicAttribute attr4 = new DynamicAttribute();
        attr4.setName("certification");
        attr4.setValue("CERT-001");
        attrs2.add(attr4);
        set2.setAttributes(attrs2);

        model1.getDynamicAttributeSets().add(set1);
        model1.getDynamicAttributeSets().add(set2);
        records.add(model1);

        // Second record with nested arrays
        ParentModel model2 = new ParentModel();
        model2.setTestField("testValue2");
        model2.setRefName("parent2");

        DynamicAttributeSet set3 = new DynamicAttributeSet();
        set3.setName("logistics");
        List<DynamicAttribute> attrs3 = new ArrayList<>();
        DynamicAttribute attr5 = new DynamicAttribute();
        attr5.setName("shippingNumber");
        attr5.setValue("SHIP-002");
        attrs3.add(attr5);
        DynamicAttribute attr6 = new DynamicAttribute();
        attr6.setName("vatNumber");
        attr6.setValue("VAT-002");
        attrs3.add(attr6);
        set3.setAttributes(attrs3);

        model2.getDynamicAttributeSets().add(set3);
        records.add(model2);

        return records;
    }

    @Test
    public void testNestedArrayExport() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        List<ParentModel> records = getParentModelRecords();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingOutput streamingOutput = helper.streamCSVOut(
                ParentModel.class,
                records,
                null,
                ',',
                List.of(
                        "refName",
                        "testField",
                        "dynamicAttributeSets[0].attributes[1].value",  // Nested array: second attribute of first set
                        "dynamicAttributeSets[0].attributes[0].name",    // Nested array: first attribute name
                        "dynamicAttributeSets[1].attributes[0].value"    // Nested array: first attribute of second set
                ),
                "QUOTE_WHERE_ESSENTIAL",
                '"',
                Charset.forName("UTF-8"),
                false,
                true,
                List.of("ID", "Test Field", "VAT Number", "Shipping Name", "Certification"),
                null);

        streamingOutput.write(output);
        String csvOutput = output.toString(Charset.forName("UTF-8"));

        // Verify the CSV contains expected values
        assertTrue(csvOutput.contains("parent1"), "Should contain parent1");
        assertTrue(csvOutput.contains("VAT-001"), "Should contain VAT-001 from dynamicAttributeSets[0].attributes[1].value");
        assertTrue(csvOutput.contains("shippingNumber"), "Should contain shippingNumber from dynamicAttributeSets[0].attributes[0].name");
        assertTrue(csvOutput.contains("CERT-001"), "Should contain CERT-001 from dynamicAttributeSets[1].attributes[0].value");
        assertTrue(csvOutput.contains("parent2"), "Should contain parent2");

        System.out.println("Nested Array CSV Output:");
        System.out.println(csvOutput);
    }

    @Test
    public void testNestedArrayWithMultiDigitIndex() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        // Create a record with many dynamic attribute sets to test multi-digit indices
        ParentModel model = new ParentModel();
        model.setTestField("testValue");
        model.setRefName("parent10");

        for (int i = 0; i < 15; i++) {
            DynamicAttributeSet set = new DynamicAttributeSet();
            set.setName("set" + i);
            List<DynamicAttribute> attrs = new ArrayList<>();
            DynamicAttribute attr = new DynamicAttribute();
            attr.setName("attr" + i);
            attr.setValue("value" + i);
            attrs.add(attr);
            set.setAttributes(attrs);
            model.getDynamicAttributeSets().add(set);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingOutput streamingOutput = helper.streamCSVOut(
                ParentModel.class,
                List.of(model),
                null,
                ',',
                List.of(
                        "refName",
                        "dynamicAttributeSets[10].attributes[0].value",  // Multi-digit index
                        "dynamicAttributeSets[0].attributes[0].value"     // Single-digit index
                ),
                "QUOTE_WHERE_ESSENTIAL",
                '"',
                Charset.forName("UTF-8"),
                false,
                true,
                List.of("ID", "Value at Index 10", "Value at Index 0"),
                null);

        streamingOutput.write(output);
        String csvOutput = output.toString(Charset.forName("UTF-8"));

        // Verify multi-digit index works
        assertTrue(csvOutput.contains("value10"), "Should contain value10 from index [10]");
        assertTrue(csvOutput.contains("value0"), "Should contain value0 from index [0]");

        System.out.println("Multi-digit Index CSV Output:");
        System.out.println(csvOutput);
    }

    @Test
    public void testNestedArrayWithMultipleNestedProperties() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        List<ParentModel> records = getParentModelRecords();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingOutput streamingOutput = helper.streamCSVOut(
                ParentModel.class,
                records,
                null,
                ',',
                List.of(
                        "refName",
                        "testField",
                        "dynamicAttributeSets[0].attributes[0].value",  // First nested property
                        "dynamicAttributeSets[0].attributes[1].value",  // Second nested property
                        "dynamicAttributeSets[0].name",                  // Set name (not nested array)
                        "dynamicAttributeSets[1].attributes[0].value"   // Third nested property
                ),
                "QUOTE_WHERE_ESSENTIAL",
                '"',
                Charset.forName("UTF-8"),
                false,
                true,
                null,
                null);

        streamingOutput.write(output);
        String csvOutput = output.toString(Charset.forName("UTF-8"));

        // Verify multiple nested properties work together
        assertTrue(csvOutput.contains("SHIP-001"), "Should contain SHIP-001");
        assertTrue(csvOutput.contains("VAT-001"), "Should contain VAT-001");
        assertTrue(csvOutput.contains("logistics"), "Should contain logistics set name");
        assertTrue(csvOutput.contains("CERT-001"), "Should contain CERT-001");

        System.out.println("Multiple Nested Properties CSV Output:");
        System.out.println(csvOutput);
    }

    @Test
    public void testNestedArrayWithOutOfBoundsIndex() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        List<ParentModel> records = getParentModelRecords();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingOutput streamingOutput = helper.streamCSVOut(
                ParentModel.class,
                records,
                null,
                ',',
                List.of(
                        "refName",
                        "dynamicAttributeSets[0].attributes[10].value",  // Out of bounds index
                        "dynamicAttributeSets[5].attributes[0].value"   // Out of bounds set index
                ),
                "QUOTE_WHERE_ESSENTIAL",
                '"',
                Charset.forName("UTF-8"),
                false,
                true,
                null,
                null);

        streamingOutput.write(output);
        String csvOutput = output.toString(Charset.forName("UTF-8"));

        // Verify out of bounds indices return empty strings (graceful handling)
        // The CSV should still be valid, just with empty values
        assertTrue(csvOutput.contains("parent1"), "Should contain parent1");
        assertTrue(csvOutput.contains("parent2"), "Should contain parent2");

        System.out.println("Out of Bounds Index CSV Output:");
        System.out.println(csvOutput);
    }

    @Test
    public void testNestedArrayWithNullValues() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        ParentModel model = new ParentModel();
        model.setTestField("testValue");
        model.setRefName("parentNull");
        // dynamicAttributeSets is empty (initialized as empty ArrayList)

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingOutput streamingOutput = helper.streamCSVOut(
                ParentModel.class,
                List.of(model),
                null,
                ',',
                List.of(
                        "refName",
                        "dynamicAttributeSets[0].attributes[0].value"  // Null nested array
                ),
                "QUOTE_WHERE_ESSENTIAL",
                '"',
                Charset.forName("UTF-8"),
                false,
                true,
                null,
                null);

        streamingOutput.write(output);
        String csvOutput = output.toString(Charset.forName("UTF-8"));

        // Verify null/empty values are handled gracefully (empty string)
        assertTrue(csvOutput.contains("parentNull"), "Should contain parentNull");

        System.out.println("Null Values CSV Output:");
        System.out.println(csvOutput);
    }

    @Test
    public void testNestedArrayBackwardCompatibility() throws IOException {
        CSVExportHelper helper = new CSVExportHelper();

        List<CSVModel> records = getRecords();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingOutput streamingOutput = helper.streamCSVOut(
                CSVModel.class,
                records,
                null,
                ',',
                List.of("testField1", "testList[0]", "displayName"),  // Single-level array (backward compatible)
                "QUOTE_WHERE_ESSENTIAL",
                '"',
                Charset.forName("UTF-8"),
                false,
                true,
                null,
                null);

        streamingOutput.write(output);
        String csvOutput = output.toString(Charset.forName("UTF-8"));

        // Verify backward compatibility - single-level arrays still work
        assertTrue(csvOutput.contains("value1"), "Should contain value1");
        assertTrue(csvOutput.contains("value2"), "Should contain value2 from testList[0]");
        assertTrue(csvOutput.contains("testDisplayName"), "Should contain testDisplayName");

        System.out.println("Backward Compatibility CSV Output:");
        System.out.println(csvOutput);
    }

    @Test
    public void testNestedArrayWithRepositoryProjection() throws IOException {
        // This test verifies that nested array paths are correctly stripped of array indices
        // for MongoDB projection, preventing Morphia validation errors
        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {
            CSVExportHelper helper = new CSVExportHelper();

            // First, save some test data
            List<ParentModel> testRecords = getParentModelRecords();
            List<org.bson.types.ObjectId> savedIds = new ArrayList<>();
            for (ParentModel record : testRecords) {
                ParentModel saved = parentRepo.save(record);
                savedIds.add(saved.getId());
            }

            try {
                // Test CSV export using repository with nested array paths
                // This should not throw a Morphia validation error about array indices in projection paths
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                StreamingOutput streamingOutput = helper.streamCSVOut(
                        parentRepo,
                        ',',
                        List.of(
                                "refName",
                                "testField",
                                "dynamicAttributeSets[0].attributes[1].value",  // Nested array path
                                "dynamicAttributeSets[0].attributes[0].name",    // Nested array path
                                "dynamicAttributeSets[1].attributes[0].value"     // Nested array path
                        ),
                        "QUOTE_WHERE_ESSENTIAL",
                        '"',
                        Charset.forName("UTF-8"),
                        false,
                        null,  // filter
                        0,     // offset
                        -1,    // length (-1 for all)
                        true,  // prependHeaderRow
                        List.of("ID", "Test Field", "VAT Number", "Shipping Name", "Certification")
                );

                // This should not throw an exception about projection paths
                streamingOutput.write(output);
                String csvOutput = output.toString(Charset.forName("UTF-8"));

                // Verify the CSV contains expected values
                assertTrue(csvOutput.contains("parent1"), "Should contain parent1");
                assertTrue(csvOutput.contains("VAT-001"), "Should contain VAT-001 from nested array");
                assertTrue(csvOutput.contains("shippingNumber"), "Should contain shippingNumber from nested array");
                assertTrue(csvOutput.contains("CERT-001"), "Should contain CERT-001 from nested array");

                System.out.println("Repository-based Nested Array CSV Output:");
                System.out.println(csvOutput);
            } finally {
                // Clean up test data to avoid conflicts with other tests
                for (org.bson.types.ObjectId id : savedIds) {
                    try {
                        parentRepo.delete(id);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }
            }
        }
    }
}

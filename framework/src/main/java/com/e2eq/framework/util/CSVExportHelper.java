package com.e2eq.framework.util;


import com.e2eq.framework.model.persistent.base.CloseableIterator;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import jakarta.validation.ValidationException;
import org.apache.commons.beanutils.PropertyUtils;

import org.apache.commons.lang3.StringUtils;
import org.dozer.MappingException;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ParseBigDecimal;
import org.supercsv.cellprocessor.ParseDouble;
import org.supercsv.cellprocessor.ParseInt;
import org.supercsv.cellprocessor.ParseLong;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.dozer.CsvDozerBeanWriter;
import org.supercsv.io.dozer.ICsvDozerBeanWriter;
import org.supercsv.prefs.CsvPreference;
import org.supercsv.quote.AlwaysQuoteMode;
import org.supercsv.quote.NormalQuoteMode;
import org.supercsv.quote.QuoteMode;
import org.supercsv.util.CsvContext;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;

/**
 * A helper for CSV exports.
 */
public class CSVExportHelper {

    final private static Pattern ARRAY_OPERATOR_RE = Pattern.compile(
            "\\[[0-9]\\]"); // pattern to eliminate index to query mongo


    /**
     * @param clazz                the class type being streamed out as CSV
     * @param records              the collection of records to be streamed out as CSV
     * @param iterator             the closeable iterator
     * @param fieldSeparator       the character that must be used to separate fields of the same record
     * @param requestedColumns     a non-empty list of the names of the columns expected in the delimited text; if
     *                             unspecified, all columns are assumed to be requested. The list can contain duplicates
     * @param quotingStrategy      the choice of strategy for the way in which columns are quoted when they contain
     *                             values that embed the quoteChar character. One of "QUOTE_WHERE_ESSENTIAL" or
     *                             "QUOTE_ALL_COLUMNS".
     * @param quoteChar            the character that is used to surround the values of specific (or all) fields to
     *                             protect them from being fragmented up when loaded back later
     * @param chosenCharset        the charset to which the CSV file must be encoded, including in some cases the
     *                             choice of whether  a "byte order mark" (BOM) must precede the CSV data.
     *                             These specific Strings are permissible values: US-ASCII, "UTF-8-without-BOM",
     *                             "UTF-8-with-BOM", "UTF-16-with-BOM", "UTF-16BE" and "UTF-16LE"
     * @param mustUseBOM           whether a byte order mark must be used
     * @param prependHeaderRow     when set to 'true', the first row is to contain the name of each requested column
     *                             (which implies it is erroneous to set this parameter when requestedColumns hasn't
     *                             been). The names to use for each column can be overridden using the
     *                             preferredColumnName parameter
     * @param preferredColumnNames when set to 'true', the first row is to contain the name of each requested column
     *                             (which implies it is erroneous to set this parameter when requestedColumns hasn't
     *                             been). The names to use for each column can be overridden using the
     *                             preferredColumnNames parameter
     * @param nestedProperty       the nested property
     * @param <T>                  type of the record being CSV exported
     * @return a streaming output with CSV data
     * @throws ValidationException - if information the caller supplied is incorrect
     */
    public <T> StreamingOutput streamCSVOut(final Class<T> clazz, final Collection<T> records,
                                            final CloseableIterator<T> iterator,
                                            final char fieldSeparator,
                                            final List<String> requestedColumns,
                                            String quotingStrategy,
                                            final char quoteChar,
                                            final Charset chosenCharset,
                                            final boolean mustUseBOM,
                                            final boolean prependHeaderRow,
                                            List<String> preferredColumnNames,
                                            final String nestedProperty) throws
            ValidationException {

        QuoteMode qmode = new NormalQuoteMode();
        if ("QUOTE_ALL_COLUMNS".equalsIgnoreCase(quotingStrategy)) {
            qmode = new AlwaysQuoteMode();
        }
        else if (quotingStrategy != null && !"QUOTE_WHERE_ESSENTIAL".equalsIgnoreCase(quotingStrategy)) {
            throw new ValidationException(format("The value %s is not one of the supported quote strategies: %s or %s",
                    quotingStrategy, "QUOTE_ALL_COLUMNS", "QUOTE_WHERE_ESSENTIAL"));
        }

        final QuoteMode finalQmode = qmode;

        final List<String> columnHeaderNames = new ArrayList<>();

        if (prependHeaderRow) {
            if (requestedColumns == null || requestedColumns.isEmpty()) {
                throw new ValidationException("The prependHeaderRow parameter is only valid if the requestedColumns" +
                        " parameter has been specified");
            }

            if (preferredColumnNames != null && preferredColumnNames.size() > requestedColumns.size()) {
                throw new ValidationException(format("It is incorrect for the preferredColumnNames list to have %d" +
                                " items when requestedColumns has fewer (%d)",
                        preferredColumnNames.size(), requestedColumns.size()));
            }

            for (int i = 0; i < requestedColumns.size(); i++) {
                if (preferredColumnNames == null) {
                    columnHeaderNames.add(requestedColumns.get(i));
                }
                else {
                    columnHeaderNames.add(
                            i < preferredColumnNames.size() && StringUtils.isNotBlank(preferredColumnNames.get(i))
                                    ? preferredColumnNames.get(i)
                                    : requestedColumns.get(i));
                }
            }
        }

        StreamingOutput streamingOutput = new StreamingOutput() {
            @Override
            public void write(OutputStream output) throws IOException, WebApplicationException {

                if (mustUseBOM) {
                    writeBOM(output, chosenCharset);
                }

                Writer writer = new BufferedWriter(new OutputStreamWriter(output, chosenCharset), 16384);

                ListCellProcessor listProcessor = new ListCellProcessor();
                final CellProcessor[] processors = buildProcessors(clazz, requestedColumns, listProcessor);

                ICsvDozerBeanWriter beanWriter = null;

                try {
                    beanWriter = new CsvDozerBeanWriter(writer,
                            new CsvPreference.Builder(quoteChar, fieldSeparator, "\r\n")
                                    .useQuoteMode(finalQmode)
                                    .build());

                    String[] requestedColumnsAsArray = requestedColumns.toArray(new String[]{});
                    beanWriter.configureBeanMapping(clazz, requestedColumnsAsArray);

                    if (prependHeaderRow) {
                        beanWriter.writeHeader(columnHeaderNames.toArray(new String[]{}));
                    }

                    if (records != null) {

                        for (T record : records) {
                            if (nestedProperty != null) {
                                List propertyValue = (List) PropertyUtils.getProperty(record, nestedProperty);
                                List tempPropertyValue = new ArrayList<>(propertyValue);

                                if (propertyValue == null || (propertyValue instanceof List)) {
                                    if (propertyValue == null || propertyValue.isEmpty() || propertyValue.size() == 1) {
                                        beanWriter.write(record, processors);
                                    }
                                    else {
                                        for (int i = 0; i < propertyValue.size(); i++) {
                                            if (i > 0) {
                                                // Object itemInFocus = propertyValue.remove(i);
                                                // propertyValue.add(0, itemInFocus);
                                                Object itemInFocus = tempPropertyValue.remove(i);
                                                tempPropertyValue.add(0, itemInFocus);

                                                PropertyUtils.setProperty(record, nestedProperty, tempPropertyValue);
                                            }
                                            beanWriter.write(record, processors);
                                        }
                                    }
                                }
                                else {
                                    throw new ValidationException(format("requestedColumns refers to a property " +
                                            "%s whose value is not a list", nestedProperty));
                                }
                            }
                            else {
                                beanWriter.write(record, processors);
                            }

                        }
                    }
                    else if (iterator != null) {
                        try {
                            while (iterator.hasNext()) {
                                T record = iterator.next();

                                if (nestedProperty != null) {
                                    List propertyValue = (List) PropertyUtils.getProperty(record, nestedProperty);
                                    List tempPropertyValue = new ArrayList<>(propertyValue);

                                    if (propertyValue == null || (propertyValue instanceof List)) {
                                        if (propertyValue == null || propertyValue.isEmpty() || propertyValue.size()
                                                == 1) {
                                            beanWriter.write(record, processors);
                                        }
                                        else {
                                            for (int i = 0; i < propertyValue.size(); i++) {
                                                if (i > 0) {
                                                    // Object itemInFocus = propertyValue.remove(i);
                                                    // propertyValue.add(0, itemInFocus);
                                                    Object itemInFocus = tempPropertyValue.remove(i);
                                                    tempPropertyValue.add(0, itemInFocus);

                                                    PropertyUtils.setProperty(record, nestedProperty,
                                                            tempPropertyValue);
                                                }
                                                beanWriter.write(record, processors);
                                            }
                                        }
                                    }
                                    else {
                                        throw new ValidationException(format("requestedColumns refers to a property " +
                                                "%s whose value is not a list", nestedProperty));
                                    }
                                }
                                else {
                                    beanWriter.write(record, processors);
                                }

                            }
                        } finally {
                            iterator.close();
                        }
                    }
                    else {
                        throw new IllegalArgumentException("Either an iterator or a collection must be passed in");
                    }

                    writer.flush();

                } catch (NoSuchMethodException | IllegalAccessException |
                        InvocationTargetException e) {
                    writeErrorToCSV(writer, 500, e);
                    throw new WebApplicationException(e, 500);
                } catch (ValidationException | MappingException | IllegalArgumentException e) {
                    writeErrorToCSV(writer, 400, e);
                    throw new WebApplicationException(e, 400);
                } finally {
                    beanWriter.close();
                    output.close();
                }
            }

            void writeErrorToCSV(Writer writer, int status, Exception e) throws IOException {

                if (status == 400) {
                    writer.write("Incorrect information supplied: " + e.getMessage());
                }
                else {
                    writer.write("Server failed to process request: " + e.getMessage());
                }
            }
        };

        return streamingOutput;
    }

    /**
     * @param repo              the repo class to use to fetch records that be exported to CSV
     * @param fieldSeparator       the character that must be used to separate fields of the same record
     * @param requestedColumns     a non-empty list of the names of the columns expected in the delimited text; if
     *                             unspecified, all columns are assumed to be requested. It would be very unusual for
     *                             this list to contain duplicates but that is not expressly prohibited
     * @param quotingStrategy      the choice of strategy for the way in which columns are quoted when they contain
     *                             values that embed the quoteChar character. One of "QUOTE_WHERE_ESSENTIAL" or
     *                             "QUOTE_ALL_COLUMNS".
     * @param quoteChar            the character that is used to surround the values of specific (or all) fields to
     *                             protect them from being fragmented up when loaded back later
     * @param chosenCharset        the charset to which the CSV file must be encoded, including in some cases the
     *                             choice of whether a "byte order mark" (BOM) must precede the CSV data.
     *                             These specific Strings are permissible values: US-ASCII, "UTF-8-without-BOM",
     *                             "UTF-8-with-BOM", "UTF-16-with-BOM", "UTF-16BE" and "UTF-16LE"
     * @param mustUseBOM           whether a byte order mark must be used
     * @param filter               a String that represents a syntactically valid filter expression; if set to null,
     *                             the implication is that filtering must be performed
     * @param offset               the position of the record from which to start converting into delimited values
     * @param length               the maximum number of records to convert into delimited values, starting from offset
     *                             pass -1 to retrieve all
     * @param prependHeaderRow     when set to 'true', the first row is to contain the name of each requested column
     *                             (which implies it is erroneous to set this parameter when requestedColumns hasn't
     *                             been). The names to use for each column can be overridden using the
     *                             preferredColumnName parameter
     * @param preferredColumnNames when set to 'true', the first row is to contain the name of each requested column
     *                             (which implies it is erroneous to set this parameter when requestedColumns hasn't
     *                             been). The names to use for each column can be overridden using the
     *                             preferredColumnNames parameter
     * @param <T>                  type of the record being CSV exported
     * @return a streaming output with CSV data
     * @throws ValidationException          - if information the caller supplied is deemed malformed or incorrect
     */
    public <T> StreamingOutput streamCSVOut(BaseMorphiaRepo repo, final char fieldSeparator,
                                            final List<String> requestedColumns, String quotingStrategy,
                                            final char quoteChar, final Charset chosenCharset,
                                            final boolean mustUseBOM, String filter, int offset, int length,
                                            final boolean prependHeaderRow, List<String> preferredColumnNames)
            throws ValidationException {

        List<MorphiaUtils.SortParameter> sortFields = null; // Let the underlying code choose a default sort field

        ArrayList<String> copyRequestedColumns = new ArrayList<>();

        String soleNestedPropertyIfAny = screenRequestedColumns(requestedColumns, copyRequestedColumns);
        List<ProjectionField> projectionFields = new ArrayList<>();
        for (String column : copyRequestedColumns) {
            projectionFields.add(new ProjectionField(column, ProjectionField.ProjectionType.INCLUDE));
        }

        CloseableIterator<T> iterator;

        if (StringUtils.isBlank(filter)) {
            iterator = repo.getStreamByQuery(offset,
                    length,
                    null,
                    sortFields,
                    (projectionFields.isEmpty()) ? null : projectionFields);
        }
        else {
            iterator = repo.getStreamByQuery(offset,
                    length,
                    filter,
                    sortFields,
                    (projectionFields.isEmpty()) ? null : projectionFields
                    );
        }

        return streamCSVOut(repo.getPersistentClass(), null, iterator, fieldSeparator, requestedColumns,
                quotingStrategy, quoteChar,
                chosenCharset, mustUseBOM,
                prependHeaderRow, preferredColumnNames,
                soleNestedPropertyIfAny);

    }

    /**
     * @param dao                  the DAO class to use to fetch records that be exported to CSV
     * @param fieldSeparator       the character that must be used to separate fields of the same record
     * @param requestedColumns     a non-empty list of the names of the columns expected in the delimited text; if
     *                             unspecified, all columns are assumed to be requested. It would be very unusual for
     *                             this list to contain duplicates but that is not expressly prohibited
     * @param dataDomain           data domain of the current user
     * @param quotingStrategy      the choice of strategy for the way in which columns are quoted when they contain
     *                             values that embed the quoteChar character. One of "QUOTE_WHERE_ESSENTIAL" or
     *                             "QUOTE_ALL_COLUMNS".
     * @param quoteChar            the character that is used to surround the values of specific (or all) fields to
     *                             protect them from being fragmented up when loaded back later
     * @param chosenCharset        the charset to which the CSV file must be encoded, including in some cases the
     *                             choice of whether a "byte order mark" (BOM) must precede the CSV data.
     *                             These specific Strings are permissible values: US-ASCII, "UTF-8-without-BOM",
     *                             "UTF-8-with-BOM", "UTF-16-with-BOM", "UTF-16BE" and "UTF-16LE"
     * @param mustUseBOM           whether a byte order mark must be used
     * @param filter               a String that represents a syntactically valid filter expression; if set to null,
     *                             the implication is that filtering must be performed
     * @param offset               the position of the record from which to start converting into delimited values
     * @param length               the maximum number of records to convert into delimited values, starting from offset
     * @param prependHeaderRow     when set to 'true', the first row is to contain the name of each requested column
     *                             (which implies it is erroneous to set this parameter when requestedColumns hasn't
     *                             been). The names to use for each column can be overridden using the
     *                             preferredColumnName parameter
     * @param preferredColumnNames when set to 'true', the first row is to contain the name of each requested column
     *                             (which implies it is erroneous to set this parameter when requestedColumns hasn't
     *                             been). The names to use for each column can be overridden using the
     *                             preferredColumnNames parameter
     * @param <T>                  type of the record being CSV exported
     * @return a streaming output with CSV data
     * @throws ValidationException  - if information the caller supplied is deemed malformed or incorrect
     */
    public <T> StreamingOutput streamCSVOut(MorphiaRepo dao, final char fieldSeparator, final
    List<String> requestedColumns, String dataDomain, String quotingStrategy, final char quoteChar, final Charset
                                                    chosenCharset, final boolean mustUseBOM, String filter, int
                                                    offset, int length, final boolean
                                                    prependHeaderRow, List<String> preferredColumnNames) throws
            ValidationException{

        List<MorphiaUtils.SortParameter> sortFields = null; // Let the underlying code choose a default sort field

        List<String> copyRequestedColumns = new ArrayList<>();

        String soleNestedPropertyIfAny = screenRequestedColumns(requestedColumns, copyRequestedColumns);
        CloseableIterator<T> iterator;

        List<String> dataDomains = new ArrayList<>();
        dataDomains.add(dataDomain);

        if (StringUtils.isBlank(filter)) {
            iterator = dao.getStreamByQuery(offset,
                    length,
                    null,
                    null,
                    (copyRequestedColumns.isEmpty()) ? null : copyRequestedColumns);
        }
        else {
            iterator = dao.getStreamByQuery(offset,
                    length,
                    filter,
                    sortFields,
                    (copyRequestedColumns.isEmpty()) ? null : copyRequestedColumns);
        }

        return streamCSVOut(dao.getPersistentClass(), null, iterator, fieldSeparator, requestedColumns,
                quotingStrategy, quoteChar,
                chosenCharset, mustUseBOM, prependHeaderRow, preferredColumnNames,
                soleNestedPropertyIfAny);
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

    /**
     * Writes byte order mark
     *
     * @param output        the output stream to write the BOM to
     * @param chosenCharset the charset to use
     * @throws IOException when there is an I/O error
     */
    public void writeBOM(OutputStream output, Charset chosenCharset) throws IOException {

        if (chosenCharset.name().equals("UTF-8")) {
            // See https://en.wikipedia.org/wiki/Byte_order_mark#UTF-8
            output.write(0xEF);
            output.write(0xBB);
            output.write(0xBF);
        }
        else {
            // See https://en.wikipedia.org/wiki/Byte_order_mark#UTF-16
            // UTF-16 (not UTF-16BE or UTF-16LE) is big endian and so
            // the U+FFEE BOM codepoint "will appear in the sequence of
            // bytes as 0xFE followed by 0xFF"
            output.write(0xFE);
            output.write(0xFF);
        }
    }

    /**
     * Inner class to process CSV cell
     */
    public class ListCellProcessor extends CellProcessorAdaptor {

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
                }
                else {
                    return next.execute(((List) value).get(i), context);
                }

            }
            else {
                return next.execute(value, context);
            }


        }

        /**
         * Sets the index in a list
         *
         * @param index index in list
         */
        public void setIndex(int index) {

            this.i = index;
        }
    }

    private String screenRequestedColumns(List<String> requestedColumnsInParam,
                                          List<String> requestedColumnsOutParam)
            throws ValidationException {

        String soleNestedProperty = null;
        for (String col : requestedColumnsInParam) {
            String[] parts = ARRAY_OPERATOR_RE.split(col);
            if (parts.length == 1) {
                requestedColumnsOutParam.add(col);
            }
            else {
                if (soleNestedProperty == null) {
                    soleNestedProperty = parts[0];
                }
                else {
                    if (!(soleNestedProperty.equals(parts[0]))) {
                        throw new ValidationException(format("requestedColumns should not referred" +
                                " more than one nested property (%s and %s)", soleNestedProperty, parts[0]));
                    }
                }
                requestedColumnsOutParam.add(StringUtils.join(parts, ""));

                Matcher m = ARRAY_OPERATOR_RE.matcher(col);

                if (m.find()) {
                    // Guaranteed to match because ARRAY_OPERATOR_RE.split() did manage to split the string

                    if (!m.group().equals("[0]")) {
                        throw new ValidationException(format("requestedColumns with nested property %s " +
                                "should not use an index other than [0]", soleNestedProperty));
                    }
                }
            }
        }
        return soleNestedProperty;
    }
}

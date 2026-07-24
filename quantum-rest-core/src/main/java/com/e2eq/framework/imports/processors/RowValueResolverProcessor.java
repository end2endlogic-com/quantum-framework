package com.e2eq.framework.imports.processors;

import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.imports.spi.RowValueResolver;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.exception.SuperCsvCellProcessorException;
import org.supercsv.util.CsvContext;

import java.util.Map;
import java.util.function.Supplier;

/**
 * CellProcessor that invokes a RowValueResolver to compute field values.
 *
 * <p>This processor is special because it needs access to all row data,
 * not just the current cell. It uses a Supplier to lazily fetch the
 * current row data and ImportContext at processing time.</p>
 */
public class RowValueResolverProcessor extends CellProcessorAdaptor {

    private final RowValueResolver resolver;
    private final Supplier<Map<String, Object>> rowDataSupplier;
    private final Supplier<ImportContext> contextSupplier;

    /**
     * Create a resolver processor with a next processor in the chain.
     *
     * @param resolver the row value resolver to invoke
     * @param rowDataSupplier supplier for current row data
     * @param contextSupplier supplier for import context
     * @param next the next processor in the chain
     */
    public RowValueResolverProcessor(RowValueResolver resolver,
                                      Supplier<Map<String, Object>> rowDataSupplier,
                                      Supplier<ImportContext> contextSupplier,
                                      CellProcessor next) {
        super(next);
        this.resolver = resolver;
        this.rowDataSupplier = rowDataSupplier;
        this.contextSupplier = contextSupplier;
    }

    /**
     * Create a resolver processor as the final processor.
     *
     * @param resolver the row value resolver to invoke
     * @param rowDataSupplier supplier for current row data
     * @param contextSupplier supplier for import context
     */
    public RowValueResolverProcessor(RowValueResolver resolver,
                                      Supplier<Map<String, Object>> rowDataSupplier,
                                      Supplier<ImportContext> contextSupplier) {
        super();
        this.resolver = resolver;
        this.rowDataSupplier = rowDataSupplier;
        this.contextSupplier = contextSupplier;
    }

    @Override
    public Object execute(Object value, CsvContext context) {
        String inputValue = value != null ? value.toString() : null;

        Map<String, Object> rowData = rowDataSupplier != null ? rowDataSupplier.get() : null;
        ImportContext importContext = contextSupplier != null ? contextSupplier.get() : null;

        RowValueResolver.ResolveResult result = resolver.resolve(inputValue, rowData, importContext);

        if (!result.isSuccess()) {
            throw new SuperCsvCellProcessorException(
                    String.format("RowValueResolver '%s' failed: %s",
                            resolver.getName(), result.getErrorMessage()),
                    context, this);
        }

        if (result.isSkip()) {
            // Signal skip via exception that can be caught upstream
            throw new RowSkipException(result.getErrorMessage(), context, this);
        }

        Object resolvedValue = result.getValue();
        return next.execute(resolvedValue, context);
    }

    /**
     * Exception thrown when a RowValueResolver signals that a row should be skipped.
     */
    public static class RowSkipException extends SuperCsvCellProcessorException {
        public RowSkipException(String msg, CsvContext context, CellProcessor processor) {
            super(msg != null ? msg : "Row skipped by resolver", context, processor);
        }
    }
}

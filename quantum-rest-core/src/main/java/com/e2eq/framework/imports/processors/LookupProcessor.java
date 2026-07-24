package com.e2eq.framework.imports.processors;

import com.e2eq.framework.imports.service.LookupService;
import com.e2eq.framework.model.persistent.imports.LookupConfig;
import com.e2eq.framework.model.persistent.imports.LookupFailBehavior;
import org.supercsv.cellprocessor.CellProcessorAdaptor;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.util.CsvContext;

import java.util.Optional;

/**
 * SuperCSV CellProcessor that performs cross-collection lookups.
 * Uses LookupService to resolve values from other collections.
 */
public class LookupProcessor extends CellProcessorAdaptor {

    private final LookupService lookupService;
    private final LookupConfig config;
    private final String realmId;

    /**
     * Create a LookupProcessor.
     *
     * @param lookupService the lookup service to use
     * @param config the lookup configuration
     * @param realmId the realm ID for the lookup query
     */
    public LookupProcessor(LookupService lookupService, LookupConfig config, String realmId) {
        this.lookupService = lookupService;
        this.config = config;
        this.realmId = realmId;
    }

    /**
     * Create a LookupProcessor with a next processor in the chain.
     */
    public LookupProcessor(LookupService lookupService, LookupConfig config,
                           String realmId, CellProcessor next) {
        super(next);
        this.lookupService = lookupService;
        this.config = config;
        this.realmId = realmId;
    }

    @Override
    public Object execute(Object value, CsvContext context) {
        if (value == null) {
            return next.execute(null, context);
        }

        String stringValue = value.toString();

        Optional<Object> lookupResult = lookupService.lookup(stringValue, config, realmId);

        if (lookupResult.isPresent()) {
            return next.execute(lookupResult.get(), context);
        }

        // Handle lookup failure
        LookupFailBehavior behavior = config.getOnNotFound();
        if (behavior == null) {
            behavior = LookupFailBehavior.FAIL;
        }

        switch (behavior) {
            case NULL:
                return next.execute(null, context);
            case PASSTHROUGH:
                return next.execute(value, context);
            case FAIL:
            default:
                throw new org.supercsv.exception.SuperCsvCellProcessorException(
                        String.format("Lookup failed for value '%s' in collection '%s'",
                                stringValue, config.getLookupCollection()),
                        context, this);
        }
    }
}

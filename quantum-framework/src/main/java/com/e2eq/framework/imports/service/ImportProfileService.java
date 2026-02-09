package com.e2eq.framework.imports.service;

import com.e2eq.framework.imports.processors.*;
import com.e2eq.framework.imports.spi.BatchLifecycleHandler;
import com.e2eq.framework.imports.spi.FieldCalculator;
import com.e2eq.framework.imports.spi.ImportBatch;
import com.e2eq.framework.imports.spi.ImportContext;
import com.e2eq.framework.imports.spi.PreValidationTransformer;
import com.e2eq.framework.imports.spi.RowValueResolver;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.imports.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.supercsv.cellprocessor.Optional;
import org.supercsv.cellprocessor.ift.CellProcessor;

import java.util.*;

/**
 * Service that orchestrates import profile processing.
 * Builds cell processors, invokes field calculators, and applies transformations.
 */
@ApplicationScoped
public class ImportProfileService {

    private static final Logger LOG = Logger.getLogger(ImportProfileService.class);

    @Inject
    LookupService lookupService;

    @Inject
    Instance<FieldCalculator> fieldCalculators;

    @Inject
    Instance<PreValidationTransformer> preValidationTransformers;

    @Inject
    Instance<RowValueResolver> rowValueResolvers;

    @Inject
    Instance<BatchLifecycleHandler> batchLifecycleHandlers;

    /**
     * Build CellProcessors for a column based on the import profile.
     *
     * @param profile the import profile
     * @param columnName the CSV column name
     * @param realmId the realm ID for lookups
     * @param baseProcessor the base processor to wrap
     * @return the configured CellProcessor chain
     */
    public CellProcessor buildProcessorForColumn(ImportProfile profile, String columnName,
                                                  String realmId, CellProcessor baseProcessor) {
        if (profile == null) {
            return baseProcessor;
        }

        CellProcessor processor = baseProcessor;

        // Find column mapping
        ColumnMapping mapping = profile.findMappingBySourceColumn(columnName);
        if (mapping == null) {
            // No specific mapping - apply global transformations only
            if (profile.getGlobalTransformations() != null) {
                processor = new GlobalTransformProcessor(profile.getGlobalTransformations(), processor);
            }
            return processor;
        }

        // Build processor chain (applied in reverse order)
        // 1. Lookup (last applied)
        if (mapping.getLookup() != null) {
            processor = new LookupProcessor(lookupService, mapping.getLookup(), realmId, processor);
        }

        // 2. Case transform
        if (mapping.getCaseTransform() != null && mapping.getCaseTransform() != CaseTransform.NONE) {
            processor = new CaseTransformProcessor(mapping.getCaseTransform(), processor);
        }

        // 3. Regex replacement
        if (mapping.getRegexPattern() != null && !mapping.getRegexPattern().isEmpty()) {
            processor = new RegexReplaceProcessor(mapping.getRegexPattern(),
                    mapping.getRegexReplacement(), processor);
        }

        // 4. Value mapping
        if (mapping.getValueMappings() != null && !mapping.getValueMappings().isEmpty()) {
            processor = new ValueMapProcessor(mapping.getValueMappings(),
                    mapping.isValueMappingCaseSensitive(),
                    mapping.getUnmappedValueBehavior(), processor);
        }

        // 5. Global transformations (first applied)
        if (profile.getGlobalTransformations() != null) {
            processor = new GlobalTransformProcessor(profile.getGlobalTransformations(), processor);
        }

        return processor;
    }

    /**
     * Apply field calculators to a bean.
     *
     * @param profile the import profile
     * @param bean the bean to process
     * @param rowData the raw row data
     * @param context the import context
     */
    public void applyFieldCalculators(ImportProfile profile, BaseModel bean,
                                       Map<String, Object> rowData, ImportContext context) {
        if (profile == null) {
            return;
        }

        // Apply inline calculators first
        applyInlineCalculators(profile, bean, rowData);

        // Apply named field calculators
        List<String> calculatorNames = profile.getFieldCalculatorNames();
        if (calculatorNames == null || calculatorNames.isEmpty()) {
            return;
        }

        // Get all matching calculators and sort by order
        List<FieldCalculator> applicableCalculators = new ArrayList<>();
        for (FieldCalculator calculator : fieldCalculators) {
            if (calculatorNames.contains(calculator.getName()) &&
                    calculator.appliesTo(bean.getClass().asSubclass(BaseModel.class))) {
                applicableCalculators.add(calculator);
            }
        }

        applicableCalculators.sort(Comparator.comparingInt(FieldCalculator::getOrder));

        // Apply each calculator
        for (FieldCalculator calculator : applicableCalculators) {
            try {
                calculator.calculate(bean, rowData, context);
            } catch (Exception e) {
                LOG.warnf(e, "Field calculator %s failed", calculator.getName());
            }
        }
    }

    /**
     * Apply pre-validation transformers to a bean.
     *
     * @param profile the import profile
     * @param bean the bean to process
     * @param rowData the raw row data
     * @param context the import context
     * @return the transformation result
     */
    public PreValidationTransformer.TransformResult applyPreValidationTransformers(
            ImportProfile profile, BaseModel bean, Map<String, Object> rowData, ImportContext context) {

        if (profile == null) {
            return PreValidationTransformer.TransformResult.success();
        }

        List<String> transformerNames = profile.getPreValidationTransformerNames();
        if (transformerNames == null || transformerNames.isEmpty()) {
            return PreValidationTransformer.TransformResult.success();
        }

        // Get all matching transformers and sort by order
        List<PreValidationTransformer> applicableTransformers = new ArrayList<>();
        for (PreValidationTransformer transformer : preValidationTransformers) {
            if (transformerNames.contains(transformer.getName()) &&
                    transformer.appliesTo(bean.getClass().asSubclass(BaseModel.class))) {
                applicableTransformers.add(transformer);
            }
        }

        applicableTransformers.sort(Comparator.comparingInt(PreValidationTransformer::getOrder));

        // Apply each transformer
        for (PreValidationTransformer transformer : applicableTransformers) {
            try {
                PreValidationTransformer.TransformResult result =
                        transformer.transform(bean, rowData, context);

                if (!result.isSuccess() || result.isSkip()) {
                    return result; // Stop processing on error or skip
                }
            } catch (Exception e) {
                LOG.warnf(e, "Pre-validation transformer %s failed", transformer.getName());
                return PreValidationTransformer.TransformResult.error(
                        "Transformer " + transformer.getName() + " failed: " + e.getMessage());
            }
        }

        return PreValidationTransformer.TransformResult.success();
    }

    /**
     * Parse the intent from a row based on the profile configuration.
     *
     * @param profile the import profile
     * @param rowData the row data
     * @return the import intent
     */
    public ImportIntent parseIntent(ImportProfile profile, Map<String, Object> rowData) {
        if (profile == null) {
            return ImportIntent.UPSERT;
        }

        String intentColumn = profile.getIntentColumn();
        if (intentColumn == null || intentColumn.isEmpty()) {
            return profile.getDefaultIntent();
        }

        Object intentValue = rowData.get(intentColumn);
        if (intentValue == null || intentValue.toString().isEmpty()) {
            return profile.getDefaultIntent();
        }

        String intentStr = intentValue.toString().toUpperCase().trim();
        try {
            return ImportIntent.valueOf(intentStr);
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid intent value '%s', using default", intentStr);
            return profile.getDefaultIntent();
        }
    }

    /**
     * Parse headers with modifiers if enabled.
     *
     * @param profile the import profile
     * @param headers the raw CSV headers
     * @return list of parsed headers
     */
    public List<ParsedHeader> parseHeaders(ImportProfile profile, String[] headers) {
        boolean enableModifiers = profile != null && profile.isEnableHeaderModifiers();

        List<ParsedHeader> parsed = new ArrayList<>();
        for (String header : headers) {
            parsed.add(ParsedHeader.parse(header, enableModifiers));
        }
        return parsed;
    }

    private void applyInlineCalculators(ImportProfile profile, BaseModel bean,
                                        Map<String, Object> rowData) {
        List<InlineFieldCalculator> calculators = profile.getInlineCalculators();
        if (calculators == null || calculators.isEmpty()) {
            return;
        }

        for (InlineFieldCalculator calc : calculators) {
            try {
                Object value = calculateInlineValue(calc, rowData);
                if (value != null) {
                    setFieldValue(bean, calc.getFieldName(), value);
                }
            } catch (Exception e) {
                LOG.warnf(e, "Inline calculator for field %s failed", calc.getFieldName());
            }
        }
    }

    private Object calculateInlineValue(InlineFieldCalculator calc, Map<String, Object> rowData) {
        if (calc.getType() == null) {
            return null;
        }

        switch (calc.getType()) {
            case TIMESTAMP:
                return new Date();

            case UUID:
                return java.util.UUID.randomUUID().toString();

            case STATIC:
                return calc.getStaticValue();

            case COPY:
                return rowData.get(calc.getSourceField());

            case TEMPLATE:
                return expandTemplate(calc.getTemplate(), rowData);

            default:
                return null;
        }
    }

    private String expandTemplate(String template, Map<String, Object> rowData) {
        if (template == null) {
            return null;
        }

        String result = template;
        for (Map.Entry<String, Object> entry : rowData.entrySet()) {
            String placeholder = "${" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * Apply RowValueResolvers to a bean based on column mappings.
     * This runs arbitrary code per-column with access to all row data.
     *
     * @param profile the import profile
     * @param bean the bean to process
     * @param rowData the raw row data (column name to value)
     * @param context the import context
     * @return result indicating success, skip, or error
     */
    public RowValueResolver.ResolveResult applyRowValueResolvers(
            ImportProfile profile, BaseModel bean, Map<String, Object> rowData, ImportContext context) {

        if (profile == null || profile.getColumnMappings() == null) {
            return RowValueResolver.ResolveResult.success(null);
        }

        for (ColumnMapping mapping : profile.getColumnMappings()) {
            String resolverName = mapping.getRowValueResolverName();
            if (resolverName == null || resolverName.isEmpty()) {
                continue;
            }

            // Find the resolver by name
            RowValueResolver resolver = findRowValueResolver(resolverName);
            if (resolver == null) {
                LOG.warnf("RowValueResolver '%s' not found for column '%s'",
                        resolverName, mapping.getSourceColumn());
                continue;
            }

            // Check if resolver applies to this bean type
            if (!resolver.appliesTo(bean.getClass())) {
                continue;
            }

            try {
                // Get the current value from the source column
                Object inputValue = rowData.get(mapping.getSourceColumn());
                String inputStr = inputValue != null ? inputValue.toString() : null;

                // Invoke the resolver
                RowValueResolver.ResolveResult result = resolver.resolve(inputStr, rowData, context);

                if (!result.isSuccess()) {
                    return result; // Propagate error
                }

                if (result.isSkip()) {
                    return result; // Propagate skip
                }

                // Set the resolved value on the bean
                String targetField = mapping.getTargetField();
                if (targetField == null || targetField.isEmpty()) {
                    targetField = mapping.getSourceColumn();
                }

                if (result.getValue() != null) {
                    setFieldValue(bean, targetField, result.getValue());
                }

            } catch (Exception e) {
                LOG.warnf(e, "RowValueResolver '%s' failed for column '%s'",
                        resolverName, mapping.getSourceColumn());
                return RowValueResolver.ResolveResult.error(
                        "Resolver " + resolverName + " failed: " + e.getMessage());
            }
        }

        return RowValueResolver.ResolveResult.success(null);
    }

    /**
     * Find a RowValueResolver by name.
     *
     * @param name the resolver name
     * @return the resolver, or null if not found
     */
    public RowValueResolver findRowValueResolver(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        for (RowValueResolver resolver : rowValueResolvers) {
            if (name.equals(resolver.getName())) {
                return resolver;
            }
        }
        return null;
    }

    /**
     * Apply batch lifecycle handlers (beforeBatch) to an ImportBatch.
     * Handlers are invoked in order; first failure stops processing.
     *
     * @param profile the import profile
     * @param batch the import batch
     * @param context import context (realm, session, profile; rowNumber can be first row in batch)
     * @return result indicating success, partial failure, or abort
     */
    public BatchLifecycleHandler.BatchResult applyBatchLifecycleHandlers(
            ImportProfile profile,
            ImportBatch<?> batch,
            ImportContext context) {

        if (profile == null || batch == null || batch.size() == 0) {
            return BatchLifecycleHandler.BatchResult.success();
        }

        List<String> handlerNames = profile.getBatchLifecycleHandlerNames();
        if (handlerNames == null || handlerNames.isEmpty()) {
            return BatchLifecycleHandler.BatchResult.success();
        }

        Class<?> targetClass = context.getTargetClass();
        if (targetClass == null) {
            return BatchLifecycleHandler.BatchResult.success();
        }

        List<BatchLifecycleHandler> applicableHandlers = new ArrayList<>();
        for (BatchLifecycleHandler handler : batchLifecycleHandlers) {
            if (handlerNames.contains(handler.getName()) && handler.appliesTo(targetClass)) {
                applicableHandlers.add(handler);
            }
        }

        applicableHandlers.sort(Comparator.comparingInt(BatchLifecycleHandler::getOrder));

        for (BatchLifecycleHandler handler : applicableHandlers) {
            try {
                BatchLifecycleHandler.BatchResult result = handler.beforeBatch(batch, context);
                if (!result.isSuccess()) {
                    return result;
                }
            } catch (Exception e) {
                LOG.warnf(e, "BatchLifecycleHandler '%s' failed", handler.getName());
                return BatchLifecycleHandler.BatchResult.error(
                        "Batch handler " + handler.getName() + " failed: " + e.getMessage());
            }
        }

        return BatchLifecycleHandler.BatchResult.success();
    }

    /**
     * Invoke afterBatch on all configured batch lifecycle handlers.
     *
     * @param profile the import profile
     * @param batch the batch that was saved
     * @param context import context
     */
    public void invokeBatchLifecycleAfterBatch(
            ImportProfile profile,
            ImportBatch<?> batch,
            ImportContext context) {

        if (profile == null || batch == null) {
            return;
        }

        List<String> handlerNames = profile.getBatchLifecycleHandlerNames();
        if (handlerNames == null || handlerNames.isEmpty()) {
            return;
        }

        Class<?> targetClass = context.getTargetClass();
        if (targetClass == null) {
            return;
        }

        for (BatchLifecycleHandler handler : batchLifecycleHandlers) {
            if (handlerNames.contains(handler.getName()) && handler.appliesTo(targetClass)) {
                try {
                    handler.afterBatch(batch, context);
                } catch (Exception e) {
                    LOG.warnf(e, "BatchLifecycleHandler '%s' afterBatch failed", handler.getName());
                }
            }
        }
    }

    /**
     * Check if any column mappings in the profile use RowValueResolvers.
     *
     * @param profile the import profile
     * @return true if any resolvers are configured
     */
    public boolean hasRowValueResolvers(ImportProfile profile) {
        if (profile == null || profile.getColumnMappings() == null) {
            return false;
        }

        for (ColumnMapping mapping : profile.getColumnMappings()) {
            if (mapping.getRowValueResolverName() != null && !mapping.getRowValueResolverName().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void setFieldValue(Object bean, String fieldName, Object value) throws Exception {
        if (fieldName == null || fieldName.isEmpty()) {
            return;
        }

        // Try setter first
        String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        try {
            var method = bean.getClass().getMethod(setterName, value.getClass());
            method.invoke(bean, value);
            return;
        } catch (NoSuchMethodException e) {
            // Try with String parameter
            try {
                var method = bean.getClass().getMethod(setterName, String.class);
                method.invoke(bean, value.toString());
                return;
            } catch (NoSuchMethodException e2) {
                // Fall through to direct field access
            }
        }

        // Try direct field access
        Class<?> clazz = bean.getClass();
        while (clazz != null) {
            try {
                var field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(bean, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }

        LOG.warnf("Could not set field %s on %s", fieldName, bean.getClass().getSimpleName());
    }
}

package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.imports.VerticalFormatConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Processor for VERTICAL strategy.
 * Handles CSV files where multiple rows represent attributes for a single entity.
 *
 * Example CSV format:
 * entity_id,set_name,attr_name,attr_value,attr_type
 * PROD001,specs,weight,2.5,double
 * PROD001,specs,color,red,string
 * PROD001,custom,rating,5,integer
 * PROD002,specs,weight,3.0,double
 *
 * All rows with the same entity_id are grouped together.
 */
@ApplicationScoped
public class VerticalFormatProcessor {

    private static final Logger LOG = Logger.getLogger(VerticalFormatProcessor.class);

    @Inject
    DynamicAttributeTypeInferrer typeInferrer;

    /**
     * Result of processing vertical format CSV.
     * Contains grouped attributes by entity key.
     */
    public static class VerticalProcessingResult {
        private final Map<String, Map<String, DynamicAttributeSet>> attributesByEntity;
        private final List<String> errors;

        public VerticalProcessingResult() {
            this.attributesByEntity = new LinkedHashMap<>();
            this.errors = new ArrayList<>();
        }

        public Map<String, Map<String, DynamicAttributeSet>> getAttributesByEntity() {
            return attributesByEntity;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void addError(String error) {
            errors.add(error);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * Process all rows from vertical format CSV.
     *
     * @param rows list of row data maps
     * @param profile the import profile
     * @return processing result with grouped attributes
     */
    public VerticalProcessingResult processRows(List<Map<String, Object>> rows, ImportProfile profile) {
        VerticalProcessingResult result = new VerticalProcessingResult();

        VerticalFormatConfig config = profile.getVerticalFormatConfig();
        if (config == null) {
            result.addError("VerticalFormatConfig is required for VERTICAL strategy");
            return result;
        }

        String entityKeyColumn = config.getEntityKeyColumn();
        String setNameColumn = config.getSetNameColumn();
        String attrNameColumn = config.getAttrNameColumn();
        String attrValueColumn = config.getAttrValueColumn();
        String attrTypeColumn = config.getAttrTypeColumn();

        int rowNum = 0;
        for (Map<String, Object> row : rows) {
            rowNum++;

            // Get entity key
            Object entityKeyValue = row.get(entityKeyColumn);
            if (entityKeyValue == null) {
                result.addError(String.format("Row %d: Missing entity key in column '%s'",
                        rowNum, entityKeyColumn));
                continue;
            }
            String entityKey = entityKeyValue.toString();

            // Get set name
            Object setNameValue = row.get(setNameColumn);
            String setName = (setNameValue != null) ? setNameValue.toString() :
                    config.getDefaultSetName();
            if (setName == null || setName.trim().isEmpty()) {
                result.addError(String.format("Row %d: Missing set name in column '%s'",
                        rowNum, setNameColumn));
                continue;
            }

            // Get attribute name
            Object attrNameValue = row.get(attrNameColumn);
            if (attrNameValue == null || attrNameValue.toString().trim().isEmpty()) {
                result.addError(String.format("Row %d: Missing attribute name in column '%s'",
                        rowNum, attrNameColumn));
                continue;
            }
            String attrName = attrNameValue.toString();

            // Get attribute value
            Object attrValue = row.get(attrValueColumn);
            if (attrValue == null) {
                LOG.debugf("Row %d: Null value for attribute '%s', skipping", rowNum, attrName);
                continue;
            }
            String stringValue = attrValue.toString();

            // Get type (optional)
            DynamicAttributeType type = null;
            if (attrTypeColumn != null) {
                Object typeValue = row.get(attrTypeColumn);
                if (typeValue != null && !typeValue.toString().trim().isEmpty()) {
                    type = typeInferrer.parseTypeHint(typeValue.toString());
                }
            }

            // Infer type if not specified
            if (type == null) {
                type = typeInferrer.inferType(stringValue,
                        profile.getDynamicAttributeDateFormatOrDefault(),
                        profile.getDynamicAttributeDateTimeFormatOrDefault(),
                        profile.getDefaultDynamicAttributeTypeOrDefault());
            }

            // Skip excluded
            if (type == DynamicAttributeType.Exclude) {
                continue;
            }

            // Convert value
            Object convertedValue = typeInferrer.convertValue(stringValue, type,
                    profile.getDynamicAttributeDateFormatOrDefault(),
                    profile.getDynamicAttributeDateTimeFormatOrDefault());

            // Create or get entity's attribute sets
            Map<String, DynamicAttributeSet> entitySets = result.getAttributesByEntity()
                    .computeIfAbsent(entityKey, k -> new HashMap<>());

            // Create or get attribute set
            DynamicAttributeSet attrSet = entitySets.computeIfAbsent(setName, name -> {
                DynamicAttributeSet newSet = new DynamicAttributeSet();
                newSet.setName(name);
                newSet.setAttributes(new ArrayList<>());
                return newSet;
            });

            // Create dynamic attribute
            DynamicAttribute attr = DynamicAttribute.builder()
                    .name(attrName)
                    .type(type)
                    .value(convertedValue)
                    .build();

            attrSet.getAttributes().add(attr);

            LOG.tracef("Entity %s: Added attribute %s.%s = %s (type: %s)",
                    entityKey, setName, attrName, convertedValue, type);
        }

        return result;
    }

    /**
     * Get attributes for a specific entity.
     *
     * @param result the processing result
     * @param entityKey the entity key
     * @return map of setName -> DynamicAttributeSet
     */
    public Map<String, DynamicAttributeSet> getAttributesForEntity(VerticalProcessingResult result,
                                                                    String entityKey) {
        return result.getAttributesByEntity().getOrDefault(entityKey, Collections.emptyMap());
    }

    /**
     * Get all unique entity keys from the processing result.
     *
     * @param result the processing result
     * @return set of entity keys
     */
    public Set<String> getEntityKeys(VerticalProcessingResult result) {
        return result.getAttributesByEntity().keySet();
    }

    /**
     * Check if vertical format processing is configured.
     *
     * @param profile the import profile
     * @return true if vertical format is configured
     */
    public boolean isConfigured(ImportProfile profile) {
        return profile.getVerticalFormatConfig() != null;
    }

    /**
     * Validate vertical format configuration.
     *
     * @param profile the import profile
     * @return list of validation errors (empty if valid)
     */
    public List<String> validateConfiguration(ImportProfile profile) {
        List<String> errors = new ArrayList<>();

        VerticalFormatConfig config = profile.getVerticalFormatConfig();
        if (config == null) {
            errors.add("VerticalFormatConfig is required for VERTICAL strategy");
            return errors;
        }

        // Entity key column is required
        if (config.getEntityKeyColumn() == null || config.getEntityKeyColumn().trim().isEmpty()) {
            // Will use default, which is fine
        }

        // Either setNameColumn or defaultSetName must be configured
        if ((config.getSetNameColumn() == null || config.getSetNameColumn().trim().isEmpty()) &&
                (config.getDefaultSetName() == null || config.getDefaultSetName().trim().isEmpty())) {
            errors.add("Either setNameColumn or defaultSetName must be configured");
        }

        return errors;
    }
}

package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.imports.JsonColumnConfig;
import com.e2eq.framework.model.persistent.imports.JsonFormatStyle;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Processor for JSON_COLUMN strategy.
 * Handles CSV columns containing JSON data for dynamic attributes.
 *
 * Supports two JSON formats:
 *
 * COMPACT format:
 * {
 *   "setName": {
 *     "attrName": value,
 *     "otherAttr": value
 *   }
 * }
 *
 * FULL format:
 * {
 *   "setName": {
 *     "name": "setName",
 *     "attributes": [
 *       {"name": "attrName", "type": "String", "value": "foo"},
 *       {"name": "otherAttr", "type": "Integer", "value": 42}
 *     ]
 *   }
 * }
 */
@ApplicationScoped
public class JsonColumnProcessor {

    private static final Logger LOG = Logger.getLogger(JsonColumnProcessor.class);

    @Inject
    DynamicAttributeTypeInferrer typeInferrer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Process JSON column and extract dynamic attributes.
     *
     * @param jsonValue the JSON string from the CSV column
     * @param profile the import profile with configuration
     * @return map of setName -> DynamicAttributeSet
     */
    public Map<String, DynamicAttributeSet> processJson(String jsonValue, ImportProfile profile) {
        if (jsonValue == null || jsonValue.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        JsonColumnConfig config = profile.getJsonColumnConfig();
        JsonFormatStyle style = (config != null && config.getFormatStyle() != null) ?
                config.getFormatStyle() : JsonFormatStyle.COMPACT;

        try {
            JsonNode rootNode = objectMapper.readTree(jsonValue);

            if (!rootNode.isObject()) {
                LOG.warnf("JSON value is not an object: %s", jsonValue);
                return Collections.emptyMap();
            }

            return switch (style) {
                case COMPACT -> processCompactFormat(rootNode, profile);
                case FULL -> processFullFormat(rootNode, profile);
            };

        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to parse JSON: %s - %s", jsonValue, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Process COMPACT format JSON.
     * Format: {"setName": {"attrName": value, ...}, ...}
     */
    private Map<String, DynamicAttributeSet> processCompactFormat(JsonNode rootNode, ImportProfile profile) {
        Map<String, DynamicAttributeSet> result = new HashMap<>();

        Iterator<Map.Entry<String, JsonNode>> setIterator = rootNode.fields();
        while (setIterator.hasNext()) {
            Map.Entry<String, JsonNode> setEntry = setIterator.next();
            String setName = setEntry.getKey();
            JsonNode setNode = setEntry.getValue();

            if (!setNode.isObject()) {
                LOG.warnf("Set value is not an object for set '%s'", setName);
                continue;
            }

            DynamicAttributeSet attrSet = new DynamicAttributeSet();
            attrSet.setName(setName);
            attrSet.setAttributes(new ArrayList<>());

            Iterator<Map.Entry<String, JsonNode>> attrIterator = setNode.fields();
            while (attrIterator.hasNext()) {
                Map.Entry<String, JsonNode> attrEntry = attrIterator.next();
                String attrName = attrEntry.getKey();
                JsonNode valueNode = attrEntry.getValue();

                DynamicAttribute attr = createAttributeFromValue(attrName, valueNode, profile);
                if (attr != null) {
                    attrSet.getAttributes().add(attr);
                }
            }

            if (attrSet.getAttributes() != null && !attrSet.getAttributes().isEmpty()) {
                result.put(setName, attrSet);
            }
        }

        return result;
    }

    /**
     * Process FULL format JSON.
     * Format: {"setName": {"name": "setName", "attributes": [{"name": "x", "type": "String", "value": "y"}, ...]}}
     */
    private Map<String, DynamicAttributeSet> processFullFormat(JsonNode rootNode, ImportProfile profile) {
        Map<String, DynamicAttributeSet> result = new HashMap<>();

        Iterator<Map.Entry<String, JsonNode>> setIterator = rootNode.fields();
        while (setIterator.hasNext()) {
            Map.Entry<String, JsonNode> setEntry = setIterator.next();
            String setName = setEntry.getKey();
            JsonNode setNode = setEntry.getValue();

            if (!setNode.isObject()) {
                LOG.warnf("Set value is not an object for set '%s'", setName);
                continue;
            }

            DynamicAttributeSet attrSet = new DynamicAttributeSet();

            // Use explicit name if provided, otherwise use key
            if (setNode.has("name")) {
                attrSet.setName(setNode.get("name").asText());
            } else {
                attrSet.setName(setName);
            }

            attrSet.setAttributes(new ArrayList<>());

            // Process attributes array
            JsonNode attributesNode = setNode.get("attributes");
            if (attributesNode != null && attributesNode.isArray()) {
                for (JsonNode attrNode : attributesNode) {
                    DynamicAttribute attr = createAttributeFromFullNode(attrNode, profile);
                    if (attr != null) {
                        attrSet.getAttributes().add(attr);
                    }
                }
            }

            if (attrSet.getAttributes() != null && !attrSet.getAttributes().isEmpty()) {
                result.put(attrSet.getName(), attrSet);
            }
        }

        return result;
    }

    /**
     * Create DynamicAttribute from a JSON value node (COMPACT format).
     */
    private DynamicAttribute createAttributeFromValue(String name, JsonNode valueNode, ImportProfile profile) {
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }

        Object value;
        DynamicAttributeType type;

        if (valueNode.isTextual()) {
            String textValue = valueNode.asText();
            type = typeInferrer.inferType(textValue,
                    profile.getDynamicAttributeDateFormatOrDefault(),
                    profile.getDynamicAttributeDateTimeFormatOrDefault(),
                    profile.getDefaultDynamicAttributeTypeOrDefault());
            value = typeInferrer.convertValue(textValue, type,
                    profile.getDynamicAttributeDateFormatOrDefault(),
                    profile.getDynamicAttributeDateTimeFormatOrDefault());
        } else if (valueNode.isBoolean()) {
            type = DynamicAttributeType.Boolean;
            value = valueNode.asBoolean();
        } else if (valueNode.isInt()) {
            type = DynamicAttributeType.Integer;
            value = valueNode.asInt();
        } else if (valueNode.isLong()) {
            type = DynamicAttributeType.Long;
            value = valueNode.asLong();
        } else if (valueNode.isDouble() || valueNode.isFloat()) {
            type = DynamicAttributeType.Double;
            value = valueNode.asDouble();
        } else if (valueNode.isArray()) {
            // Arrays become MultiSelect with comma-separated values
            type = DynamicAttributeType.MultiSelect;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < valueNode.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(valueNode.get(i).asText());
            }
            value = sb.toString();
        } else if (valueNode.isObject()) {
            // Nested objects stored as JSON string
            type = DynamicAttributeType.Object;
            value = valueNode.toString();
        } else {
            type = DynamicAttributeType.String;
            value = valueNode.asText();
        }

        return DynamicAttribute.builder()
                .name(name)
                .type(type)
                .value(value)
                .build();
    }

    /**
     * Create DynamicAttribute from a full attribute node (FULL format).
     * Expected format: {"name": "attrName", "type": "String", "value": "foo", ...}
     */
    private DynamicAttribute createAttributeFromFullNode(JsonNode attrNode, ImportProfile profile) {
        if (attrNode == null || !attrNode.isObject()) {
            return null;
        }

        // Name is required
        JsonNode nameNode = attrNode.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            LOG.warn("Attribute missing required 'name' field");
            return null;
        }
        String name = nameNode.asText();

        // Value is required
        JsonNode valueNode = attrNode.get("value");
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }

        // Type is optional
        DynamicAttributeType type = null;
        JsonNode typeNode = attrNode.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            type = typeInferrer.parseTypeHint(typeNode.asText());
        }

        // Convert value based on type
        Object value;
        if (type != null) {
            if (valueNode.isTextual()) {
                value = typeInferrer.convertValue(valueNode.asText(), type,
                        profile.getDynamicAttributeDateFormatOrDefault(),
                        profile.getDynamicAttributeDateTimeFormatOrDefault());
            } else {
                value = extractJsonValue(valueNode);
            }
        } else {
            // Infer type from value
            if (valueNode.isTextual()) {
                String textValue = valueNode.asText();
                type = typeInferrer.inferType(textValue,
                        profile.getDynamicAttributeDateFormatOrDefault(),
                        profile.getDynamicAttributeDateTimeFormatOrDefault(),
                        profile.getDefaultDynamicAttributeTypeOrDefault());
                value = typeInferrer.convertValue(textValue, type,
                        profile.getDynamicAttributeDateFormatOrDefault(),
                        profile.getDynamicAttributeDateTimeFormatOrDefault());
            } else {
                value = extractJsonValue(valueNode);
                type = inferTypeFromValue(value);
            }
        }

        DynamicAttribute.DynamicAttributeBuilder builder = DynamicAttribute.builder()
                .name(name)
                .type(type)
                .value(value);

        // Optional fields
        if (attrNode.has("label")) {
            builder.label(attrNode.get("label").asText());
        }
        if (attrNode.has("description")) {
            builder.description(attrNode.get("description").asText());
        }
        if (attrNode.has("required")) {
            builder.required(attrNode.get("required").asBoolean());
        }
        if (attrNode.has("hidden")) {
            builder.hidden(attrNode.get("hidden").asBoolean());
        }

        return builder.build();
    }

    /**
     * Extract Java value from JSON node.
     */
    private Object extractJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(extractJsonValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            return node.toString();
        }
        return node.asText();
    }

    /**
     * Infer type from Java value.
     */
    private DynamicAttributeType inferTypeFromValue(Object value) {
        if (value == null) {
            return DynamicAttributeType.String;
        }
        if (value instanceof Boolean) {
            return DynamicAttributeType.Boolean;
        }
        if (value instanceof Integer) {
            return DynamicAttributeType.Integer;
        }
        if (value instanceof Long) {
            return DynamicAttributeType.Long;
        }
        if (value instanceof Float) {
            return DynamicAttributeType.Float;
        }
        if (value instanceof Double) {
            return DynamicAttributeType.Double;
        }
        if (value instanceof List) {
            return DynamicAttributeType.MultiSelect;
        }
        return DynamicAttributeType.String;
    }

    /**
     * Get the JSON column name from profile configuration.
     *
     * @param profile the import profile
     * @return the column name or null if not configured
     */
    public String getJsonColumnName(ImportProfile profile) {
        JsonColumnConfig config = profile.getJsonColumnConfig();
        return config != null ? config.getColumnName() : null;
    }

    /**
     * Check if JSON column processing is configured.
     *
     * @param profile the import profile
     * @return true if JSON column is configured
     */
    public boolean isConfigured(ImportProfile profile) {
        return getJsonColumnName(profile) != null;
    }
}

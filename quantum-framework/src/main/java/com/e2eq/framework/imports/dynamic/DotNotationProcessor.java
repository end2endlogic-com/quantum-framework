package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttribute;
import com.e2eq.framework.model.persistent.base.DynamicAttributeSet;
import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import com.e2eq.framework.model.persistent.imports.DynamicAttributeMapping;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.imports.ParsedDynamicHeader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * Processor for DOT_NOTATION strategy.
 * Handles CSV columns with format: dyn.setName.attrName[:type]
 *
 * Examples:
 * - dyn.custom.color -> set "custom", attribute "color", type inferred
 * - dyn.specs.weight:double -> set "specs", attribute "weight", type Double
 * - dyn.meta.tags:multiselect -> set "meta", attribute "tags", type MultiSelect
 */
@ApplicationScoped
public class DotNotationProcessor {

    private static final Logger LOG = Logger.getLogger(DotNotationProcessor.class);

    @Inject
    DynamicAttributeTypeInferrer typeInferrer;

    /**
     * Parse all dynamic attribute headers from CSV column names.
     *
     * @param headers the CSV headers
     * @param profile the import profile with configuration
     * @return map of column index to parsed header info
     */
    public Map<Integer, ParsedDynamicHeader> parseHeaders(String[] headers, ImportProfile profile) {
        Map<Integer, ParsedDynamicHeader> result = new HashMap<>();
        String prefix = profile.getDynamicAttributePrefixOrDefault();

        for (int i = 0; i < headers.length; i++) {
            String header = headers[i];
            if (header != null && header.startsWith(prefix)) {
                ParsedDynamicHeader parsed = parseHeader(header, prefix, profile);
                if (parsed != null) {
                    result.put(i, parsed);
                    LOG.debugf("Parsed dynamic header at index %d: %s", i, parsed);
                }
            }
        }

        return result;
    }

    /**
     * Parse a single dynamic attribute header.
     *
     * @param header the header string
     * @param prefix the prefix (e.g., "dyn.")
     * @param profile the import profile
     * @return parsed header info or null if invalid
     */
    public ParsedDynamicHeader parseHeader(String header, String prefix, ImportProfile profile) {
        if (header == null || !header.startsWith(prefix)) {
            return null;
        }

        // Remove prefix: "dyn.custom.color:string" -> "custom.color:string"
        String remainder = header.substring(prefix.length());

        // Check for explicit mapping first
        DynamicAttributeMapping explicitMapping = profile.findDynamicAttributeMapping(header);
        if (explicitMapping != null) {
            return ParsedDynamicHeader.builder()
                    .originalHeader(header)
                    .setName(explicitMapping.getTargetSetName())
                    .attributeName(explicitMapping.getTargetAttributeName())
                    .type(explicitMapping.getType())
                    .dateFormat(explicitMapping.getDateFormat() != null ?
                            explicitMapping.getDateFormat() : profile.getDynamicAttributeDateFormatOrDefault())
                    .dateTimeFormat(explicitMapping.getDateTimeFormat() != null ?
                            explicitMapping.getDateTimeFormat() : profile.getDynamicAttributeDateTimeFormatOrDefault())
                    .valid(true)
                    .build();
        }

        // Parse format: setName.attrName[:type]
        String setName;
        String attrName;
        DynamicAttributeType type = null;

        // Check for type suffix
        int colonIndex = remainder.lastIndexOf(':');
        String pathPart = remainder;
        if (colonIndex > 0) {
            pathPart = remainder.substring(0, colonIndex);
            String typeHint = remainder.substring(colonIndex + 1);
            type = typeInferrer.parseTypeHint(typeHint);
        }

        // Split path into setName and attrName
        int dotIndex = pathPart.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= pathPart.length() - 1) {
            LOG.warnf("Invalid dynamic header format (missing set or attribute name): %s", header);
            return ParsedDynamicHeader.builder()
                    .originalHeader(header)
                    .valid(false)
                    .errorMessage("Invalid format: expected dyn.setName.attrName[:type]")
                    .build();
        }

        setName = pathPart.substring(0, dotIndex);
        attrName = pathPart.substring(dotIndex + 1);

        // Handle nested attribute names (e.g., "nested.value" -> keep as single attribute name)
        // This allows for attributes like "dyn.custom.nested.value" where "nested.value" is the attr name

        return ParsedDynamicHeader.builder()
                .originalHeader(header)
                .setName(setName)
                .attributeName(attrName)
                .type(type)
                .dateFormat(profile.getDynamicAttributeDateFormatOrDefault())
                .dateTimeFormat(profile.getDynamicAttributeDateTimeFormatOrDefault())
                .valid(true)
                .build();
    }

    /**
     * Process a single CSV row and extract dynamic attributes.
     *
     * @param rowData the row data as column name -> value map
     * @param parsedHeaders map of column index to parsed header info
     * @param headers the original headers array
     * @param profile the import profile
     * @return map of setName -> DynamicAttributeSet with populated attributes
     */
    public Map<String, DynamicAttributeSet> processRow(Map<String, Object> rowData,
                                                        Map<Integer, ParsedDynamicHeader> parsedHeaders,
                                                        String[] headers,
                                                        ImportProfile profile) {
        Map<String, DynamicAttributeSet> result = new HashMap<>();

        for (Map.Entry<Integer, ParsedDynamicHeader> entry : parsedHeaders.entrySet()) {
            int columnIndex = entry.getKey();
            ParsedDynamicHeader parsed = entry.getValue();

            if (!parsed.isValid()) {
                continue;
            }

            // Get value from row data
            String header = headers[columnIndex];
            Object rawValue = rowData.get(header);

            if (rawValue == null) {
                continue; // Skip null values
            }

            String stringValue = rawValue.toString();
            if (stringValue.trim().isEmpty()) {
                continue; // Skip empty values
            }

            // Determine type (explicit > inferred)
            DynamicAttributeType type = parsed.getType();
            if (type == null) {
                type = typeInferrer.inferType(stringValue,
                        parsed.getDateFormat(),
                        parsed.getDateTimeFormat(),
                        profile.getDefaultDynamicAttributeTypeOrDefault());
            }

            // Skip excluded types
            if (type == DynamicAttributeType.Exclude) {
                continue;
            }

            // Convert value
            Object convertedValue = typeInferrer.convertValue(stringValue, type,
                    parsed.getDateFormat(), parsed.getDateTimeFormat());

            // Create or get attribute set
            DynamicAttributeSet attrSet = result.computeIfAbsent(parsed.getSetName(), name -> {
                DynamicAttributeSet newSet = new DynamicAttributeSet();
                newSet.setName(name);
                newSet.setAttributes(new ArrayList<>());
                return newSet;
            });

            // Create dynamic attribute
            DynamicAttribute attr = DynamicAttribute.builder()
                    .name(parsed.getAttributeName())
                    .type(type)
                    .value(convertedValue)
                    .build();

            attrSet.getAttributes().add(attr);

            LOG.tracef("Created dynamic attribute: %s.%s = %s (type: %s)",
                    parsed.getSetName(), parsed.getAttributeName(), convertedValue, type);
        }

        return result;
    }

    /**
     * Check if any headers match the dynamic attribute pattern.
     *
     * @param headers the CSV headers
     * @param profile the import profile
     * @return true if at least one dynamic attribute column exists
     */
    public boolean hasDynamicColumns(String[] headers, ImportProfile profile) {
        String prefix = profile.getDynamicAttributePrefixOrDefault();
        for (String header : headers) {
            if (header != null && header.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the column names that are dynamic attribute columns.
     *
     * @param headers the CSV headers
     * @param profile the import profile
     * @return list of dynamic attribute column names
     */
    public List<String> getDynamicColumnNames(String[] headers, ImportProfile profile) {
        List<String> result = new ArrayList<>();
        String prefix = profile.getDynamicAttributePrefixOrDefault();
        for (String header : headers) {
            if (header != null && header.startsWith(prefix)) {
                result.add(header);
            }
        }
        return result;
    }
}

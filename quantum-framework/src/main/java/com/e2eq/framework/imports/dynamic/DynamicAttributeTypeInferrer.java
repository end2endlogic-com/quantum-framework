package com.e2eq.framework.imports.dynamic;

import com.e2eq.framework.model.persistent.base.DynamicAttributeType;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Service for inferring DynamicAttributeType from string values.
 * Used when type is not explicitly specified in column header or mapping.
 */
@ApplicationScoped
public class DynamicAttributeTypeInferrer {

    private static final Logger LOG = Logger.getLogger(DynamicAttributeTypeInferrer.class);

    // Patterns for type detection
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile(
            "^(true|false|yes|no|y|n|1|0)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTEGER_PATTERN = Pattern.compile(
            "^-?\\d{1,9}$");
    private static final Pattern LONG_PATTERN = Pattern.compile(
            "^-?\\d{10,18}$");
    private static final Pattern DOUBLE_PATTERN = Pattern.compile(
            "^-?\\d+\\.\\d+$");
    private static final Pattern FLOAT_PATTERN = Pattern.compile(
            "^-?\\d+\\.\\d+[fF]$");

    // Common date/datetime patterns
    private static final String[] DATE_PATTERNS = {
            "yyyy-MM-dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy",
            "yyyy/MM/dd",
            "dd-MM-yyyy",
            "MM-dd-yyyy"
    };

    private static final String[] DATETIME_PATTERNS = {
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "MM/dd/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
    };

    /**
     * Infer the DynamicAttributeType from a string value.
     *
     * @param value           the string value to analyze
     * @param dateFormat      optional date format hint
     * @param dateTimeFormat  optional datetime format hint
     * @param defaultType     the default type if inference fails
     * @return the inferred DynamicAttributeType
     */
    public DynamicAttributeType inferType(String value, String dateFormat,
                                           String dateTimeFormat, DynamicAttributeType defaultType) {
        if (value == null || value.trim().isEmpty()) {
            return defaultType;
        }

        String trimmed = value.trim();

        // Try boolean first (most specific)
        if (isBoolean(trimmed)) {
            return DynamicAttributeType.Boolean;
        }

        // Try integer
        if (isInteger(trimmed)) {
            return DynamicAttributeType.Integer;
        }

        // Try long
        if (isLong(trimmed)) {
            return DynamicAttributeType.Long;
        }

        // Try float (explicit suffix)
        if (isFloat(trimmed)) {
            return DynamicAttributeType.Float;
        }

        // Try double
        if (isDouble(trimmed)) {
            return DynamicAttributeType.Double;
        }

        // Try datetime (before date, since datetime is more specific)
        if (isDateTime(trimmed, dateTimeFormat)) {
            return DynamicAttributeType.DateTime;
        }

        // Try date
        if (isDate(trimmed, dateFormat)) {
            return DynamicAttributeType.Date;
        }

        // Default to string
        return defaultType;
    }

    /**
     * Infer type using default formats.
     */
    public DynamicAttributeType inferType(String value) {
        return inferType(value, null, null, DynamicAttributeType.String);
    }

    /**
     * Parse a type hint from column header suffix.
     * Supports: :string, :text, :int, :integer, :long, :float, :double,
     *           :date, :datetime, :bool, :boolean, :select, :multiselect
     *
     * @param typeHint the type hint string (case-insensitive)
     * @return the DynamicAttributeType or null if not recognized
     */
    public DynamicAttributeType parseTypeHint(String typeHint) {
        if (typeHint == null || typeHint.trim().isEmpty()) {
            return null;
        }

        String hint = typeHint.trim().toLowerCase();

        return switch (hint) {
            case "string", "str", "s" -> DynamicAttributeType.String;
            case "text", "txt" -> DynamicAttributeType.Text;
            case "integer", "int", "i" -> DynamicAttributeType.Integer;
            case "long", "l" -> DynamicAttributeType.Long;
            case "float", "f" -> DynamicAttributeType.Float;
            case "double", "d", "decimal" -> DynamicAttributeType.Double;
            case "date" -> DynamicAttributeType.Date;
            case "datetime", "timestamp", "ts" -> DynamicAttributeType.DateTime;
            case "boolean", "bool", "b" -> DynamicAttributeType.Boolean;
            case "select" -> DynamicAttributeType.Select;
            case "multiselect", "multi" -> DynamicAttributeType.MultiSelect;
            case "regex" -> DynamicAttributeType.Regex;
            case "object", "obj" -> DynamicAttributeType.Object;
            case "objectref", "ref" -> DynamicAttributeType.ObjectRef;
            case "exclude", "skip" -> DynamicAttributeType.Exclude;
            default -> {
                LOG.warnf("Unrecognized type hint: %s", typeHint);
                yield null;
            }
        };
    }

    /**
     * Convert a string value to the appropriate Java type based on DynamicAttributeType.
     *
     * @param value          the string value
     * @param type           the target type
     * @param dateFormat     format for Date type
     * @param dateTimeFormat format for DateTime type
     * @return the converted value, or the original string if conversion fails
     */
    public Object convertValue(String value, DynamicAttributeType type,
                               String dateFormat, String dateTimeFormat) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String trimmed = value.trim();

        try {
            return switch (type) {
                case String, Text, Regex -> trimmed;
                case Integer -> java.lang.Integer.parseInt(trimmed);
                case Long -> java.lang.Long.parseLong(trimmed);
                case Float -> java.lang.Float.parseFloat(trimmed.replaceAll("[fF]$", ""));
                case Double -> java.lang.Double.parseDouble(trimmed);
                case Boolean -> parseBoolean(trimmed);
                case Date -> parseDate(trimmed, dateFormat);
                case DateTime -> parseDateTime(trimmed, dateTimeFormat);
                case Select, MultiSelect -> trimmed; // Stored as string, validated elsewhere
                case Object, ObjectRef -> trimmed; // Stored as string, needs further processing
                case Exclude -> null; // Explicitly excluded
            };
        } catch (Exception e) {
            LOG.warnf("Failed to convert value '%s' to type %s: %s", value, type, e.getMessage());
            return trimmed; // Return as string on failure
        }
    }

    private boolean isBoolean(String value) {
        return BOOLEAN_PATTERN.matcher(value).matches();
    }

    private boolean isInteger(String value) {
        if (!INTEGER_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            java.lang.Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isLong(String value) {
        if (!LONG_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            java.lang.Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isFloat(String value) {
        return FLOAT_PATTERN.matcher(value).matches();
    }

    private boolean isDouble(String value) {
        if (!DOUBLE_PATTERN.matcher(value).matches()) {
            return false;
        }
        try {
            java.lang.Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDate(String value, String formatHint) {
        // Try hint format first
        if (formatHint != null) {
            try {
                LocalDate.parse(value, DateTimeFormatter.ofPattern(formatHint));
                return true;
            } catch (DateTimeParseException e) {
                // Continue to try other formats
            }
        }

        // Try common formats
        for (String pattern : DATE_PATTERNS) {
            try {
                LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
                return true;
            } catch (DateTimeParseException e) {
                // Try next pattern
            }
        }
        return false;
    }

    private boolean isDateTime(String value, String formatHint) {
        // Try hint format first
        if (formatHint != null) {
            try {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern(formatHint));
                return true;
            } catch (DateTimeParseException e) {
                // Continue to try other formats
            }
        }

        // Try common formats
        for (String pattern : DATETIME_PATTERNS) {
            try {
                LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern));
                return true;
            } catch (DateTimeParseException e) {
                // Try next pattern
            }
        }
        return false;
    }

    private Boolean parseBoolean(String value) {
        String lower = value.toLowerCase();
        return switch (lower) {
            case "true", "yes", "y", "1" -> true;
            case "false", "no", "n", "0" -> false;
            default -> Boolean.parseBoolean(value);
        };
    }

    private LocalDate parseDate(String value, String formatHint) {
        // Try hint format first
        if (formatHint != null) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern(formatHint));
            } catch (DateTimeParseException e) {
                // Continue to try other formats
            }
        }

        // Try common formats
        for (String pattern : DATE_PATTERNS) {
            try {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException e) {
                // Try next pattern
            }
        }

        // Last resort: ISO format
        return LocalDate.parse(value);
    }

    private LocalDateTime parseDateTime(String value, String formatHint) {
        // Try hint format first
        if (formatHint != null) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(formatHint));
            } catch (DateTimeParseException e) {
                // Continue to try other formats
            }
        }

        // Try common formats
        for (String pattern : DATETIME_PATTERNS) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException e) {
                // Try next pattern
            }
        }

        // Last resort: ISO format
        return LocalDateTime.parse(value);
    }
}

package com.e2eq.ontology.core;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON writer matching Python's
 * {@code json.dumps(obj, sort_keys=True, separators=(",", ":"))} with
 * {@code ensure_ascii=True}: keys sorted at every level, no whitespace,
 * non-ASCII characters escaped as lowercase {@code \\uXXXX} (UTF-16 units, so
 * astral code points become surrogate-pair escapes). See
 * helixor-ontologies/docs/HASH_SPEC.md §2.
 */
final class CanonicalJson {
    private CanonicalJson() {}

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            appendString(out, text);
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            out.append(value);
        } else if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            long asLong = number.longValue();
            if (asDouble == (double) asLong) {
                out.append(asLong);
            } else {
                throw new IllegalArgumentException("Non-integer numbers are not part of the canonical TBox form: " + value);
            }
        } else if (value instanceof Map<?, ?> map) {
            appendObject(out, map);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                append(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported canonical JSON value type: " + value.getClass());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sorted.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) out.append(',');
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            append(out, entry.getValue());
        }
        out.append('}');
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

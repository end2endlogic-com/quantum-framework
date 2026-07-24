package com.e2eq.framework.service.contract;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical OpenAPI contract hashing for SDK drift detection.
 *
 * <p>Must produce byte-identical output to the SDK generator's Python
 * canonicalization ({@code json.dumps(spec, sort_keys=True,
 * separators=(",", ":"), ensure_ascii=True)}): recursively sorted object keys,
 * compact separators, ASCII-escaped strings with lowercase {@code \\uXXXX}
 * escapes. The algorithm identifier is part of the wire contract — bump it in
 * BOTH implementations together if the canonicalization ever changes.
 *
 * <p>v1 deliberately rejects non-integral number literals: none of the
 * platform's OpenAPI contracts contain them, and Java/Python float formatting
 * differs in ways that would produce silent hash mismatches. Failing fast at
 * hash time is the correct posture; extend the algorithm version if a contract
 * legitimately needs float literals.
 */
public final class CanonicalSpecHash {

    public static final String ALGORITHM = "sha256-canonical-json-v1";

    private CanonicalSpecHash() {
    }

    public static String sha256(JsonNode spec) {
        String canonical = canonicalize(spec);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            out.append('{');
            boolean first = true;
            for (String name : names) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(name, out);
                out.append(':');
                write(node.get(name), out);
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
            return;
        }
        if (node.isTextual()) {
            writeString(node.textValue(), out);
            return;
        }
        if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
            return;
        }
        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                out.append(node.bigIntegerValue().toString());
                return;
            }
            throw new IllegalArgumentException(
                "Contract canonicalization " + ALGORITHM + " does not support non-integral number literal "
                    + node.asText()
                    + "; platform OpenAPI contracts must not contain float literals (extend the algorithm version if one is required)");
        }
        throw new IllegalArgumentException(
            "Contract canonicalization " + ALGORITHM + " cannot serialize node type " + node.getNodeType());
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // Python ensure_ascii escapes control chars and everything >= 0x7f,
                    // using lowercase hex; astral chars appear as surrogate-pair escapes,
                    // which matches escaping each UTF-16 unit here.
                    if (c < 0x20 || c >= 0x7f) {
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

package com.e2eq.framework.rest.converters;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@Provider
public class PairParamConverterProvider implements ParamConverterProvider {

    @Override
    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
        if (rawType.equals(Pair[].class)) {
            return new PairArrayParamConverter<>();
        }
        return null;
    }

    /**
     * Parses {@code [key:value,key:'quoted, value']} (brackets optional) into update pairs.
     *
     * <p>Keys are letters, digits and underscores and name a top-level field; values run to the next
     * comma unless single-quoted. The whole parameter must parse: an entry that does not fit the
     * grammar (for example a nested path such as {@code a.b:1}) is rejected with a 400 naming the
     * entry, never skipped or truncated to the part that happens to match, because a partial parse
     * would apply the update to a different field than the caller named.</p>
     */
    public static class PairArrayParamConverter<T> implements ParamConverter<T> {
        @Override
        @SuppressWarnings("unchecked")
        public T fromString(String value) {
            return (T) parse(value);
        }

        static Pair<String, Object>[] parse(String value) {
            String body = value == null ? "" : value.trim();
            // The runtime hands array parameters over as a list string, so a single
            // pairs=[a:1] arrives as [[a:1]]; peel brackets only while they enclose everything.
            while (enclosedByBrackets(body)) {
                body = body.substring(1, body.length() - 1).trim();
            }

            List<Pair<String, Object>> pairs = new ArrayList<>();
            int pos = 0;
            int len = body.length();
            while (pos < len) {
                pos = skipWhitespace(body, pos);
                if (pos >= len) {
                    break;
                }
                int entryStart = pos;

                int keyEnd = pos;
                while (keyEnd < len && isKeyChar(body.charAt(keyEnd))) {
                    keyEnd++;
                }
                if (keyEnd == pos || keyEnd >= len || body.charAt(keyEnd) != ':') {
                    throw malformed(body, entryStart,
                            "expected key:value where the key is letters, digits or underscores; nested paths are not supported");
                }
                String key = body.substring(pos, keyEnd);
                pos = keyEnd + 1;

                String entryValue;
                if (pos < len && body.charAt(pos) == '\'') {
                    int close = body.indexOf('\'', pos + 1);
                    if (close < 0) {
                        throw malformed(body, entryStart, "unterminated quoted value");
                    }
                    entryValue = body.substring(pos + 1, close);
                    pos = skipWhitespace(body, close + 1);
                    if (pos < len && body.charAt(pos) != ',') {
                        throw malformed(body, entryStart, "unexpected text after quoted value");
                    }
                } else {
                    int comma = body.indexOf(',', pos);
                    int valueEnd = comma < 0 ? len : comma;
                    if (valueEnd == pos) {
                        throw malformed(body, entryStart, "missing value");
                    }
                    entryValue = body.substring(pos, valueEnd);
                    pos = valueEnd;
                }
                pairs.add(Pair.of(key, entryValue));

                if (pos < len) {
                    // At a separating comma; a trailing comma leaves nothing to parse.
                    pos++;
                    if (skipWhitespace(body, pos) >= len) {
                        throw malformed(body, pos - 1, "trailing comma");
                    }
                }
            }

            @SuppressWarnings("unchecked")
            Pair<String, Object>[] result = pairs.toArray(new Pair[0]);
            return result;
        }

        /** True when body opens with '[' whose matching ']' (outside quotes) is the last character. */
        private static boolean enclosedByBrackets(String body) {
            if (body.length() < 2 || body.charAt(0) != '[' || body.charAt(body.length() - 1) != ']') {
                return false;
            }
            int depth = 0;
            boolean quoted = false;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\'') {
                    quoted = !quoted;
                } else if (!quoted && c == '[') {
                    depth++;
                } else if (!quoted && c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i == body.length() - 1;
                    }
                }
            }
            return false;
        }

        private static boolean isKeyChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        private static int skipWhitespace(String s, int pos) {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            return pos;
        }

        private static BadRequestException malformed(String body, int at, String reason) {
            int comma = body.indexOf(',', at);
            String entry = body.substring(at, comma < 0 ? body.length() : comma).trim();
            return new BadRequestException("Malformed pairs entry '" + entry + "': " + reason);
        }

        @Override
        public String toString(T value) {
            // Implement your logic to convert Pair[] to a string
            StringBuilder stringBuilder = new StringBuilder();
            for (Pair pair : (Pair[]) value) {
                stringBuilder.append(pair.getKey()).append(":").append(pair.getValue()).append(",");
            }
            // Remove the trailing comma
            if (stringBuilder.length() > 0) {
                stringBuilder.setLength(stringBuilder.length() - 1);
            }
            return stringBuilder.toString();
        }
    }

}

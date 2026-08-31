package com.e2eq.framework.service.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-operation wire hashing for generated-SDK handshakes.
 *
 * <p>Must produce byte-identical output to helixor-sdk-gen
 * {@code canonical_operation_sha256}. Whole-document {@link CanonicalSpecHash}
 * remains diagnostic; login compatibility uses {@code POST /security/login}.
 *
 * <p>Bump {@link #ALGORITHM} in this class and the Python generator together
 * if the identity document ever changes.
 */
public final class CanonicalOperationHash {

    public static final String ALGORITHM = "sha256-canonical-json-operation-v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> HTTP_METHODS = Set.of(
        "get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final Set<String> COSMETIC = Set.of(
        "description",
        "summary",
        "title",
        "example",
        "examples",
        "externalDocs",
        "tags",
        "operationId",
        "servers",
        "xml");

    private CanonicalOperationHash() {
    }

    public static String identityKey(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    public static String sha256(JsonNode spec, String method, String path) {
        return CanonicalSpecHash.sha256(wireIdentity(spec, method, path));
    }

    public static Map<String, Map<String, String>> sha256ByOperation(JsonNode spec) {
        Map<String, Map<String, String>> operations = new LinkedHashMap<>();
        JsonNode paths = spec.path("paths");
        if (!paths.isObject()) {
            return operations;
        }
        Iterator<String> pathNames = paths.fieldNames();
        List<String> sortedPaths = new ArrayList<>();
        pathNames.forEachRemaining(sortedPaths::add);
        sortedPaths.sort(null);
        for (String path : sortedPaths) {
            JsonNode pathItem = paths.get(path);
            if (pathItem != null && pathItem.has("$ref")) {
                pathItem = resolveNode(spec, pathItem, new HashSet<>());
            }
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            for (String method : HTTP_METHODS) {
                if (!pathItem.has(method) || !pathItem.get(method).isObject()) {
                    continue;
                }
                String key = identityKey(method, path);
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("sha256", sha256(spec, method, path));
                entry.put("method", method.toUpperCase(Locale.ROOT));
                entry.put("path", path);
                JsonNode operationId = pathItem.get(method).get("operationId");
                if (operationId != null && operationId.isTextual() && !operationId.textValue().isBlank()) {
                    entry.put("operationId", operationId.textValue());
                }
                operations.put(key, entry);
            }
        }
        return operations;
    }

    static JsonNode wireIdentity(JsonNode spec, String method, String path) {
        JsonNode paths = spec.path("paths");
        if (!paths.isObject()) {
            throw new IllegalArgumentException("OpenAPI document has no object-valued paths section");
        }
        JsonNode pathItem = paths.get(path);
        if (pathItem == null || pathItem.isNull()) {
            throw new IllegalArgumentException("OpenAPI document has no path " + path);
        }
        pathItem = resolveNode(spec, pathItem, new HashSet<>());
        if (!pathItem.isObject()) {
            throw new IllegalArgumentException("OpenAPI path item for " + path + " did not resolve to an object");
        }
        String methodLower = method.toLowerCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(methodLower)) {
            throw new IllegalArgumentException("Unsupported HTTP method for operation identity: " + method);
        }
        JsonNode operation = pathItem.get(methodLower);
        if (operation == null || !operation.isObject()) {
            throw new IllegalArgumentException(
                "OpenAPI document has no operation " + method.toUpperCase(Locale.ROOT) + " " + path);
        }

        JsonNode requestBody = operation.get("requestBody");
        if (requestBody != null && !requestBody.isNull()) {
            requestBody = stripCosmetic(resolveNode(spec, requestBody, new HashSet<>()));
        } else {
            requestBody = NullNode.getInstance();
        }

        ObjectNode responses = MAPPER.createObjectNode();
        JsonNode rawResponses = operation.get("responses");
        if (rawResponses != null && rawResponses.isObject()) {
            Iterator<String> statuses = rawResponses.fieldNames();
            while (statuses.hasNext()) {
                String status = statuses.next();
                JsonNode raw = rawResponses.get(status);
                JsonNode resolved = raw != null && raw.isObject() ? raw : MAPPER.createObjectNode();
                responses.set(status, stripCosmetic(resolveNode(spec, resolved, new HashSet<>())));
            }
        }

        JsonNode security;
        if (operation.has("security")) {
            JsonNode declared = operation.get("security");
            security = declared != null && declared.isArray() ? declared : MAPPER.createArrayNode();
        } else {
            JsonNode root = spec.get("security");
            security = root != null && root.isArray() ? root : MAPPER.createArrayNode();
        }
        security = stripCosmetic(resolveNode(spec, security, new HashSet<>()));

        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("method", methodLower.toUpperCase(Locale.ROOT));
        identity.set("parameters", mergedParameters(spec, pathItem.get("parameters"), operation.get("parameters")));
        identity.put("path", path);
        identity.set("requestBody", requestBody);
        identity.set("responses", responses);
        identity.set("security", security);
        return identity;
    }

    private static ArrayNode mergedParameters(JsonNode spec, JsonNode pathParameters, JsonNode operationParameters) {
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        appendParameters(spec, pathParameters, merged);
        appendParameters(spec, operationParameters, merged);
        List<String> keys = new ArrayList<>(merged.keySet());
        keys.sort(Comparator.naturalOrder());
        ArrayNode out = MAPPER.createArrayNode();
        for (String key : keys) {
            out.add(merged.get(key));
        }
        return out;
    }

    private static void appendParameters(JsonNode spec, JsonNode rawParameters, Map<String, JsonNode> merged) {
        if (rawParameters == null || !rawParameters.isArray()) {
            return;
        }
        for (JsonNode raw : rawParameters) {
            if (raw == null || !raw.isObject()) {
                continue;
            }
            JsonNode resolved = stripCosmetic(resolveNode(spec, raw, new HashSet<>()));
            if (!resolved.isObject()) {
                continue;
            }
            String location = resolved.path("in").asText("");
            String name = resolved.path("name").asText("");
            merged.put(location + "\0" + name, resolved);
        }
    }

    private static JsonNode lookupRef(JsonNode spec, String ref) {
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Unsupported OpenAPI reference for operation identity: " + ref);
        }
        JsonNode node = spec;
        for (String part : ref.substring(2).split("/")) {
            String decoded = part.replace("~1", "/").replace("~0", "~");
            if (node == null || !node.isObject() || !node.has(decoded)) {
                throw new IllegalArgumentException("Unresolved OpenAPI reference for operation identity: " + ref);
            }
            node = node.get(decoded);
        }
        return node;
    }

    private static JsonNode resolveNode(JsonNode spec, JsonNode node, Set<String> seen) {
        if (node == null || node.isNull()) {
            return node == null ? NullNode.getInstance() : node;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                out.add(resolveNode(spec, item, seen));
            }
            return out;
        }
        if (!node.isObject()) {
            return node;
        }
        JsonNode refNode = node.get("$ref");
        if (refNode != null && refNode.isTextual() && refNode.textValue().startsWith("#/")) {
            String ref = refNode.textValue();
            ObjectNode extras = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!"$ref".equals(field.getKey())) {
                    extras.set(field.getKey(), resolveNode(spec, field.getValue(), seen));
                }
            }
            if (seen.contains(ref)) {
                ObjectNode cycle = MAPPER.createObjectNode();
                cycle.put("$ref", ref);
                extras.fields().forEachRemaining(entry -> cycle.set(entry.getKey(), entry.getValue()));
                return stripCosmetic(cycle);
            }
            Set<String> nestedSeen = new HashSet<>(seen);
            nestedSeen.add(ref);
            JsonNode resolved = resolveNode(spec, lookupRef(spec, ref), nestedSeen);
            if (extras.size() > 0 && resolved.isObject()) {
                ObjectNode merged = resolved.deepCopy();
                extras.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
                resolved = merged;
            }
            return stripCosmetic(resolved);
        }
        ObjectNode out = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            out.set(field.getKey(), resolveNode(spec, field.getValue(), seen));
        }
        return stripCosmetic(out);
    }

    private static JsonNode stripCosmetic(JsonNode node) {
        if (node == null || node.isNull()) {
            return node == null ? NullNode.getInstance() : node;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                out.add(stripCosmetic(item));
            }
            return out;
        }
        if (!node.isObject()) {
            return node;
        }
        ObjectNode out = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (COSMETIC.contains(key) || key.startsWith("x-")) {
                continue;
            }
            out.set(key, stripCosmetic(field.getValue()));
        }
        return out;
    }
}

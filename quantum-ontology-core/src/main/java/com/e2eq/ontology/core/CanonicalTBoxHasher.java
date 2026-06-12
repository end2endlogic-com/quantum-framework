package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cross-stack canonical TBox hash per helixor-ontologies/docs/HASH_SPEC.md.
 *
 * <p>This MUST stay byte-identical with the Python reference implementation
 * ({@code helixor_ontologies.models.TBox.stable_hash()}): canonical JSON with
 * sorted keys, compact separators, lowercase {@code \\uXXXX} escapes for all
 * non-ASCII characters, SHA-256 over UTF-8 bytes, lowercase hex digest. The
 * golden vector ({@code conformance-vectors/hash-golden.json}) pins it.
 *
 * <p>Note this is distinct from the legacy {@link TBoxHasher}: that hash uses
 * camelCase keys, drops pack metadata, sorts chains, and is not byte-stable
 * across stacks. New pack-identity code must use this class; the legacy hash
 * remains for stored {@code tboxHash} compatibility until migration (Q1 in
 * UNIFIED_ONTOLOGY_DESIGN.md).
 */
public final class CanonicalTBoxHasher {
    private CanonicalTBoxHasher() {}

    /** Pack-level metadata that lives outside the Java TBox record. */
    public record PackMetadata(String id, String name, int version, String openness,
                               String source, String sourceVersion) {
        public static PackMetadata closedPack(String id, String name, int version) {
            return new PackMetadata(id, name, version, "closed", null, null);
        }
    }

    /** Hash a TBox built from the Java records plus pack metadata. */
    public static String hashTBox(TBox tbox, PackMetadata meta) {
        Map<String, Object> root = new TreeMap<>();
        root.put("version", meta.version());
        root.put("id", meta.id());
        root.put("name", meta.name());
        root.put("openness", normalizeOpenness(meta.openness()));
        root.put("source", meta.source());
        root.put("source_version", meta.sourceVersion());

        Map<String, Object> classes = new TreeMap<>();
        for (ClassDef c : tbox.classes().values()) {
            classes.put(c.name(), classDict(c.name(), c.parents(), c.disjointWith(), c.sameAs(),
                    c.label(), c.aliases(), c.metadata()));
        }
        root.put("classes", classes);

        Map<String, Object> properties = new TreeMap<>();
        for (PropertyDef p : tbox.properties().values()) {
            properties.put(p.name(), propertyDict(p.name(),
                    p.domain().orElse(null), p.range().orElse(null),
                    p.inverseOf().orElse(null), p.transitive(), p.symmetric(), p.functional(),
                    p.subPropertyOf(), p.inferred(), p.label(), p.aliases(), p.metadata()));
        }
        root.put("properties", properties);

        List<Object> chains = new ArrayList<>();
        for (PropertyChainDef ch : tbox.propertyChains()) {
            chains.add(chainDict(ch.chain(), ch.implies()));
        }
        root.put("property_chains", chains);
        return sha256Hex(CanonicalJson.write(root));
    }

    /**
     * Hash a pack in authored (YAML/JSON mapping) form — classes/properties as
     * lists of mappings with {@code id} keys and alias key spellings, exactly
     * as the Python loader accepts them.
     */
    @SuppressWarnings("unchecked")
    public static String hashPackMapping(Map<String, Object> pack) {
        Map<String, Object> root = new TreeMap<>();
        Object version = pack.get("version");
        root.put("version", version instanceof Number number ? number.intValue() : 1);
        root.put("id", optionalString(pack.get("id")));
        root.put("name", optionalString(pack.get("name")));
        Object openness = pack.get("openness") != null ? pack.get("openness") : pack.get("ontology_mode");
        root.put("openness", normalizeOpenness(optionalString(openness)));
        root.put("source", optionalString(pack.get("source")));
        Object sourceVersion = pack.get("source_version") != null ? pack.get("source_version") : pack.get("sourceVersion");
        root.put("source_version", optionalString(sourceVersion));

        Map<String, Object> classes = new TreeMap<>();
        for (Map<String, Object> item : mappings(pack.get("classes"))) {
            String name = defName(item);
            if (name == null) continue;
            classes.put(name, classDict(name,
                    firstList(item, "parents", "subClassOf", "sub_class_of"),
                    firstList(item, "disjointWith", "disjoint_with"),
                    firstList(item, "sameAs", "same_as"),
                    optionalString(item.get("label")),
                    firstList(item, "aliases"),
                    (Map<String, Object>) item.getOrDefault("metadata", Map.of())));
        }
        root.put("classes", classes);

        Map<String, Object> properties = new TreeMap<>();
        for (Map<String, Object> item : mappings(pack.get("properties"))) {
            String name = defName(item);
            if (name == null) continue;
            String inverseOf = optionalString(item.get("inverseOf") != null ? item.get("inverseOf") : item.get("inverse_of"));
            properties.put(name, propertyDict(name,
                    optionalString(item.get("domain")),
                    optionalString(item.get("range")),
                    inverseOf,
                    Boolean.TRUE.equals(item.get("transitive")),
                    Boolean.TRUE.equals(item.get("symmetric")),
                    functionalFlag(item),
                    firstList(item, "subPropertyOf", "sub_property_of"),
                    Boolean.TRUE.equals(item.get("inferred")),
                    optionalString(item.get("label")),
                    firstList(item, "aliases"),
                    (Map<String, Object>) item.getOrDefault("metadata", Map.of())));
        }
        root.put("properties", properties);

        List<Object> chains = new ArrayList<>();
        for (Map<String, Object> item : mappings(pack.get("chains"))) {
            List<String> chain = stringList(item.get("chain"));
            chains.add(chainDict(chain, String.valueOf(item.getOrDefault("implies", "")).trim()));
        }
        root.put("property_chains", chains);
        return sha256Hex(CanonicalJson.write(root));
    }

    private static Map<String, Object> classDict(String name, Collection<String> parents,
                                                 Collection<String> disjointWith, Collection<String> sameAs,
                                                 String label, Collection<String> aliases,
                                                 Map<String, Object> metadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("parents", sorted(parents));
        data.put("disjoint_with", sorted(disjointWith));
        data.put("same_as", sorted(sameAs));
        data.put("label", label);
        data.put("aliases", sorted(aliases));
        data.put("metadata", metadata);
        return data;
    }

    private static Map<String, Object> propertyDict(String name, String domain, String range,
                                                    String inverseOf, boolean transitive, boolean symmetric,
                                                    boolean functional, Collection<String> subPropertyOf,
                                                    boolean inferred, String label, Collection<String> aliases,
                                                    Map<String, Object> metadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("domain", domain);
        data.put("range", range);
        data.put("inverse", inverseOf != null && !inverseOf.isBlank());
        data.put("inverse_of", inverseOf);
        data.put("transitive", transitive);
        data.put("symmetric", symmetric);
        data.put("functional", functional);
        data.put("sub_property_of", sorted(subPropertyOf));
        data.put("inferred", inferred);
        data.put("label", label);
        data.put("aliases", sorted(aliases));
        data.put("metadata", metadata);
        return data;
    }

    private static Map<String, Object> chainDict(List<String> chain, String implies) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chain", new ArrayList<>(chain));
        data.put("implies", implies);
        return data;
    }

    static String normalizeOpenness(String value) {
        String text = value == null || value.isBlank() ? "open" : value.trim().toLowerCase(Locale.ROOT);
        if (Set.of("mutable", "discovery", "discoverable").contains(text)) return "open";
        if (Set.of("strict", "quantum", "saas", "snapshot", "factual", "versioned", "factual_snapshot").contains(text)) {
            return "closed";
        }
        if (!Set.of("open", "closed").contains(text)) {
            throw new IllegalArgumentException("Unsupported ontology openness mode: " + value);
        }
        return text;
    }

    private static boolean functionalFlag(Map<String, Object> item) {
        String relation = optionalString(item.get("relation"));
        if (relation != null) {
            String upper = relation.toUpperCase(Locale.ROOT);
            return upper.equals("ONE_TO_ONE") || upper.equals("MANY_TO_ONE");
        }
        return Boolean.TRUE.equals(item.get("functional"));
    }

    private static String defName(Map<String, Object> item) {
        String name = optionalString(item.get("id") != null ? item.get("id") : item.get("name"));
        return name;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mappings(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private static List<String> firstList(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null) {
                return stringList(value);
            }
        }
        return List.of();
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static List<String> sorted(Collection<String> values) {
        return values == null ? List.of() : new ArrayList<>(new TreeSet<>(values));
    }

    private static String optionalString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String sha256Hex(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute canonical TBox hash", e);
        }
    }
}

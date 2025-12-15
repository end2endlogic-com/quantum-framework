package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes a stable hash of a TBox by canonicalizing to sorted JSON.
 */
public final class TBoxHasher {
    private TBoxHasher() {}
    
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    
    public static String computeHash(TBox tbox) {
        try {
            Map<String, Object> canonical = canonicalize(tbox);
            String json = MAPPER.writeValueAsString(canonical);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute TBox hash", e);
        }
    }
    
    private static Map<String, Object> canonicalize(TBox tbox) {
        Map<String, Object> result = new TreeMap<>();
        
        // Classes
        Map<String, Object> classes = new TreeMap<>();
        for (ClassDef c : tbox.classes().values()) {
            Map<String, Object> cData = new TreeMap<>();
            cData.put("name", c.name());
            cData.put("parents", new TreeSet<>(c.parents()));
            cData.put("disjointWith", new TreeSet<>(c.disjointWith()));
            cData.put("sameAs", new TreeSet<>(c.sameAs()));
            classes.put(c.name(), cData);
        }
        result.put("classes", classes);
        
        // Properties
        Map<String, Object> properties = new TreeMap<>();
        for (PropertyDef p : tbox.properties().values()) {
            Map<String, Object> pData = new TreeMap<>();
            pData.put("name", p.name());
            pData.put("domain", p.domain().orElse(null));
            pData.put("range", p.range().orElse(null));
            pData.put("inverse", p.inverse());
            pData.put("inverseOf", p.inverseOf().orElse(null));
            pData.put("transitive", p.transitive());
            pData.put("symmetric", p.symmetric());
            pData.put("functional", p.functional());
            pData.put("subPropertyOf", new TreeSet<>(p.subPropertyOf()));
            pData.put("inferred", p.inferred());
            properties.put(p.name(), pData);
        }
        result.put("properties", properties);
        
        // Chains (sorted by implies then chain)
        List<Map<String, Object>> chains = tbox.propertyChains().stream()
                .sorted(Comparator.comparing(PropertyChainDef::implies)
                        .thenComparing(ch -> String.join(",", ch.chain())))
                .map(ch -> {
                    Map<String, Object> chData = new TreeMap<>();
                    chData.put("chain", new ArrayList<>(ch.chain()));
                    chData.put("implies", ch.implies());
                    return chData;
                })
                .collect(Collectors.toList());
        result.put("propertyChains", chains);
        
        return result;
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

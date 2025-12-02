package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Utility for computing deterministic hashes of TBox content.
 * Used to detect changes and avoid unnecessary persistence operations.
 */
public final class TBoxHasher {
    
    private TBoxHasher() {
        // Utility class
    }
    
    /**
     * Compute SHA-256 hash of TBox content for change detection.
     * The hash is deterministic and will be the same for identical TBox content.
     * 
     * @param tbox the TBox to hash
     * @return SHA-256 hash as hex string
     */
    public static String computeHash(TBox tbox) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            
            // Hash classes in sorted order for determinism
            if (tbox.classes() != null) {
                tbox.classes().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                        ClassDef def = e.getValue();
                        md.update(def.name().getBytes(StandardCharsets.UTF_8));
                        def.parents().stream().sorted().forEach(p -> 
                            md.update(p.getBytes(StandardCharsets.UTF_8)));
                        def.disjointWith().stream().sorted().forEach(d -> 
                            md.update(d.getBytes(StandardCharsets.UTF_8)));
                        def.sameAs().stream().sorted().forEach(s -> 
                            md.update(s.getBytes(StandardCharsets.UTF_8)));
                    });
            }
            
            // Hash properties in sorted order for determinism
            if (tbox.properties() != null) {
                tbox.properties().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                        PropertyDef def = e.getValue();
                        md.update(def.name().getBytes(StandardCharsets.UTF_8));
                        def.domain().ifPresent(d -> md.update(d.getBytes(StandardCharsets.UTF_8)));
                        def.range().ifPresent(r -> md.update(r.getBytes(StandardCharsets.UTF_8)));
                        if (def.inverse()) md.update("inverse".getBytes(StandardCharsets.UTF_8));
                        def.inverseOf().ifPresent(i -> md.update(i.getBytes(StandardCharsets.UTF_8)));
                        if (def.transitive()) md.update("transitive".getBytes(StandardCharsets.UTF_8));
                        if (def.symmetric()) md.update("symmetric".getBytes(StandardCharsets.UTF_8));
                        if (def.functional()) md.update("functional".getBytes(StandardCharsets.UTF_8));
                        def.subPropertyOf().stream().sorted().forEach(s -> 
                            md.update(s.getBytes(StandardCharsets.UTF_8)));
                    });
            }
            
            // Hash chains in sorted order for determinism
            if (tbox.propertyChains() != null) {
                tbox.propertyChains().stream()
                    .sorted((a, b) -> {
                        int chainCmp = a.chain().toString().compareTo(b.chain().toString());
                        return chainCmp != 0 ? chainCmp : a.implies().compareTo(b.implies());
                    })
                    .forEach(chain -> {
                        chain.chain().forEach(c -> md.update(c.getBytes(StandardCharsets.UTF_8)));
                        md.update(chain.implies().getBytes(StandardCharsets.UTF_8));
                    });
            }
            
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute TBox hash", e);
        }
    }
}


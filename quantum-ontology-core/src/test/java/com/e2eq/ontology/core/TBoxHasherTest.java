package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TBoxHasherTest {

    @Test
    void testHashStability() {
        TBox tbox = createSampleTBox();
        
        String hash1 = TBoxHasher.computeHash(tbox);
        String hash2 = TBoxHasher.computeHash(tbox);
        
        assertEquals(hash1, hash2, "Hash should be stable for same TBox");
    }

    @Test
    void testHashDifferentForDifferentTBox() {
        TBox tbox1 = createSampleTBox();
        
        Map<String, ClassDef> classes2 = Map.of(
            "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of()),
            "Organization", new ClassDef("Organization", Set.of(), Set.of(), Set.of())
        );
        Map<String, PropertyDef> props2 = Map.of(
            "memberOf", new PropertyDef("memberOf", Optional.of("Person"), Optional.of("Organization"),
                false, Optional.empty(), false, false, false, Set.of(), false)
        );
        TBox tbox2 = new TBox(classes2, props2, List.of());
        
        String hash1 = TBoxHasher.computeHash(tbox1);
        String hash2 = TBoxHasher.computeHash(tbox2);
        
        assertNotEquals(hash1, hash2, "Different TBoxes should have different hashes");
    }

    @Test
    void testHashIndependentOfMapOrder() {
        // Create two TBoxes with same content but different map insertion order
        Map<String, ClassDef> classes1 = new LinkedHashMap<>();
        classes1.put("Person", new ClassDef("Person", Set.of(), Set.of(), Set.of()));
        classes1.put("Agent", new ClassDef("Agent", Set.of(), Set.of(), Set.of()));
        
        Map<String, ClassDef> classes2 = new LinkedHashMap<>();
        classes2.put("Agent", new ClassDef("Agent", Set.of(), Set.of(), Set.of()));
        classes2.put("Person", new ClassDef("Person", Set.of(), Set.of(), Set.of()));
        
        TBox tbox1 = new TBox(classes1, Map.of(), List.of());
        TBox tbox2 = new TBox(classes2, Map.of(), List.of());
        
        String hash1 = TBoxHasher.computeHash(tbox1);
        String hash2 = TBoxHasher.computeHash(tbox2);
        
        assertEquals(hash1, hash2, "Hash should be independent of map insertion order");
    }

    @Test
    void testHashIncludesAllFields() {
        Map<String, ClassDef> classes = Map.of(
            "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of())
        );
        
        Map<String, PropertyDef> props1 = Map.of(
            "knows", new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), false, true, false, Set.of(), false)
        );
        
        Map<String, PropertyDef> props2 = Map.of(
            "knows", new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), false, false, false, Set.of(), false)
        );
        
        TBox tbox1 = new TBox(classes, props1, List.of());
        TBox tbox2 = new TBox(classes, props2, List.of());
        
        String hash1 = TBoxHasher.computeHash(tbox1);
        String hash2 = TBoxHasher.computeHash(tbox2);
        
        assertNotEquals(hash1, hash2, "Hash should change when property attributes differ");
    }

    @Test
    void testHashFormat() {
        TBox tbox = createSampleTBox();
        String hash = TBoxHasher.computeHash(tbox);
        
        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hash should be 64 hex characters");
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }

    private TBox createSampleTBox() {
        Map<String, ClassDef> classes = Map.of(
            "Person", new ClassDef("Person", Set.of(), Set.of(), Set.of())
        );
        
        Map<String, PropertyDef> props = Map.of(
            "knows", new PropertyDef("knows", Optional.of("Person"), Optional.of("Person"),
                false, Optional.empty(), false, true, false, Set.of(), false)
        );
        
        return new TBox(classes, props, List.of());
    }
}

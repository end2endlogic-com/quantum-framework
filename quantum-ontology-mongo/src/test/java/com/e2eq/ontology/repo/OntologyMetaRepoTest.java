package com.e2eq.ontology.repo;

import com.e2eq.ontology.model.OntologyMeta;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OntologyMetaRepo singleton and atomic operations.
 */
@QuarkusTest
class OntologyMetaRepoTest {

    @Inject
    OntologyMetaRepo repo;

    @Test
    void testSingletonUpsert() {
        // First call creates singleton
        OntologyMeta meta1 = repo.upsertObservation(1, "/test.yaml", false);
        assertNotNull(meta1);
        assertEquals("global", meta1.getRefName());
        assertEquals(1, meta1.getYamlVersion());

        // Second call updates same singleton
        OntologyMeta meta2 = repo.upsertObservation(2, "/test2.yaml", true);
        assertNotNull(meta2);
        assertEquals("global", meta2.getRefName());
        assertEquals(2, meta2.getYamlVersion());
        assertTrue(meta2.isReindexRequired());

        // Verify only one document exists
        Optional<OntologyMeta> singleton = repo.getSingleton();
        assertTrue(singleton.isPresent());
        assertEquals(2, singleton.get().getYamlVersion());
    }

    @Test
    void testMarkAppliedUpsert() {
        // Mark as applied (creates if missing)
        OntologyMeta meta = repo.markApplied("hash123", "tbox456", 5);
        assertNotNull(meta);
        assertEquals("global", meta.getRefName());
        assertEquals("hash123", meta.getYamlHash());
        assertEquals("tbox456", meta.getTboxHash());
        assertEquals(5, meta.getYamlVersion());
        assertFalse(meta.isReindexRequired());
        assertNotNull(meta.getAppliedAt());

        // Update with new hashes
        OntologyMeta meta2 = repo.markApplied("hash789", "tbox999", 6);
        assertEquals("hash789", meta2.getYamlHash());
        assertEquals("tbox999", meta2.getTboxHash());
        assertEquals(6, meta2.getYamlVersion());

        // Verify still only one document
        Optional<OntologyMeta> singleton = repo.getSingleton();
        assertTrue(singleton.isPresent());
        assertEquals("hash789", singleton.get().getYamlHash());
    }

    @Test
    void testAtomicOperations() {
        // Simulate concurrent updates (both should succeed, last write wins)
        OntologyMeta meta1 = repo.upsertObservation(1, "/v1.yaml", false);
        OntologyMeta meta2 = repo.upsertObservation(2, "/v2.yaml", true);

        // Both operations succeeded
        assertNotNull(meta1);
        assertNotNull(meta2);

        // Final state reflects last write
        Optional<OntologyMeta> singleton = repo.getSingleton();
        assertTrue(singleton.isPresent());
        assertEquals(2, singleton.get().getYamlVersion());
        assertEquals("/v2.yaml", singleton.get().getSource());
    }

    @Test
    void testMarkAppliedPreservesObservation() {
        // First observe
        repo.upsertObservation(10, "/observed.yaml", true);

        // Then mark as applied
        OntologyMeta applied = repo.markApplied("hashABC", "tboxDEF", 10);

        // Verify both observation and applied fields are set
        assertEquals(10, applied.getYamlVersion());
        assertEquals("hashABC", applied.getYamlHash());
        assertEquals("tboxDEF", applied.getTboxHash());
        assertFalse(applied.isReindexRequired()); // Cleared by markApplied
        assertNotNull(applied.getAppliedAt());
    }
}

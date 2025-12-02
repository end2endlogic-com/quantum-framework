package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to ensure OntologyCoreProducers can load YAML ontology from classpath
 * and produce a unified OntologyRegistry even when Morphia is not available.
 */
@QuarkusTest
public class OntologyCoreProducersIntegrationTest {

    @Inject
    OntologyCoreProducers producers;

    @InjectMock
    OntologyTBoxRepo tboxRepo;

    @Test
    public void producesRegistry_withYamlOverlay_whenMorphiaMissing() {
        // Make sure repo returns empty so YAML overlay path is used
        Mockito.when(tboxRepo.findLatest()).thenReturn(Optional.empty());

        OntologyRegistry reg = producers.ontologyRegistry();

        assertNotNull(reg, "Registry should not be null");
        assertTrue(reg.propertyOf("placedBy").isPresent(), "YAML property should be available");
        assertTrue(reg.propertyOf("orderShipsToRegion").isPresent(), "YAML implied property should be present");
        assertFalse(reg.propertyChains().isEmpty(), "Chains from YAML should be loaded");
        // Expect the 4 chains from the sample YAML
        assertEquals(4, reg.propertyChains().size());
    }
}

package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.OntologyRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to ensure OntologyCoreProducers can load YAML ontology from classpath
 * and produce a unified OntologyRegistry even when Morphia is not available.
 */
public class OntologyCoreProducersIntegrationTest {

    @Test
    public void producesRegistry_withYamlOverlay_whenMorphiaMissing() {
        OntologyCoreProducers producers = new OntologyCoreProducers();
        // Do not inject MorphiaDatastore so that only YAML path is used (classpath /ontology.yaml)
        OntologyRegistry reg = producers.ontologyRegistry();

        assertNotNull(reg, "Registry should not be null");
        assertTrue(reg.propertyOf("placedBy").isPresent(), "YAML property should be available");
        assertTrue(reg.propertyOf("orderShipsToRegion").isPresent(), "YAML implied property should be present");
        assertFalse(reg.propertyChains().isEmpty(), "Chains from YAML should be loaded");
        // Expect the 4 chains from the sample YAML
        assertEquals(4, reg.propertyChains().size());
    }
}

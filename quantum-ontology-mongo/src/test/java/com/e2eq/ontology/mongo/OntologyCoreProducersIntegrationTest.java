package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.OntologyRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;

/**
 * Integration test to ensure OntologyCoreProducers produces a working OntologyRegistry
 * by merging Morphia annotations and YAML overlay.
 */
@QuarkusTest
public class OntologyCoreProducersIntegrationTest {

    @Inject
    OntologyRegistry registry;

    @Test
    public void producesRegistry_loadsFromAnnotationsAndYaml() {
        assertNotNull(registry, "Registry should not be null");
        
        // Registry loads from Morphia annotations + YAML overlay
        // Verify key properties are present
        assertTrue(registry.propertyOf("placedBy").isPresent(), 
            "Property 'placedBy' should be available. Loaded: " + registry.properties().keySet());
        assertTrue(registry.propertyOf("memberOf").isPresent(), 
            "Property 'memberOf' should be available");
        assertTrue(registry.propertyOf("placedInOrg").isPresent(), 
            "Property 'placedInOrg' should be available");
        
        // Verify at least one chain is loaded
        assertFalse(registry.propertyChains().isEmpty(), 
            "At least one chain should be loaded. Found: " + registry.propertyChains().size());
    }
}

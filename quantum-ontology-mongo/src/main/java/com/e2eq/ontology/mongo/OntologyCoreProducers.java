package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.InMemoryOntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry;
import dev.morphia.MorphiaDatastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OntologyCoreProducers {

    @Inject
    MorphiaDatastore morphiaDatastore;

    @Produces
    @Singleton
    public OntologyRegistry ontologyRegistry() {
        // Try to build ontology from Morphia model annotations; fallback to empty registry
        try {
            if (morphiaDatastore != null && morphiaDatastore.getMapper() != null) {
                MorphiaOntologyLoader loader = new MorphiaOntologyLoader(morphiaDatastore);
                OntologyRegistry reg = loader.load();
                // If nothing discovered, return empty to preserve behavior
                if (isEmpty(reg)) {
                    return emptyRegistry();
                }
                return reg;
            }
        } catch (Throwable ignored) {
            // fall through to empty
        }
        return emptyRegistry();
    }

    private boolean isEmpty(OntologyRegistry reg) {
        // crude check: try resolving a non-existent class/property and ensure there are no chains
        return reg.propertyChains().isEmpty()
                && reg.classOf("__none__").isEmpty()
                && reg.propertyOf("__none__").isEmpty();
    }

    private OntologyRegistry emptyRegistry() {
        OntologyRegistry.TBox empty = new OntologyRegistry.TBox(Map.of(), Map.of(), List.of());
        return new InMemoryOntologyRegistry(empty);
    }
}

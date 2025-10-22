package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.InMemoryOntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class OntologyCoreProducers {

    @Produces
    @Singleton
    public OntologyRegistry ontologyRegistry() {
        // Provide an empty default registry; applications can override with their own producer
        OntologyRegistry.TBox empty = new OntologyRegistry.TBox(Map.of(), Map.of(), List.of());
        return new InMemoryOntologyRegistry(empty);
    }
}

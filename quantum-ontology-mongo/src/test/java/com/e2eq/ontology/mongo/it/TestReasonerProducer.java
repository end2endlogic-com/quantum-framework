package com.e2eq.ontology.mongo.it;

import com.e2eq.ontology.core.ForwardChainingReasoner;
import com.e2eq.ontology.core.Reasoner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Test-scoped producer for the Reasoner bean to satisfy OntologyMaterializer injection during @QuarkusTest.
 */
public class TestReasonerProducer {

    @Produces
    @ApplicationScoped
    public Reasoner reasoner() {
        return new ForwardChainingReasoner();
    }
}

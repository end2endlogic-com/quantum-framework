package com.e2eq.ontology.mongo;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Forces the OntologyRegistry producer to run eagerly at application startup
 * so that package scanning and ontology assembly occur even if no component
 * injects the registry immediately.
 */
@Startup
@ApplicationScoped
public class OntologyStartupInitializer {

    @Inject
    com.e2eq.ontology.runtime.TenantOntologyRegistryProvider registryProvider; // injection triggers provider at startup

    public OntologyStartupInitializer() {
        Log.debug("OntologyStartupInitializer constructed");
    }
}

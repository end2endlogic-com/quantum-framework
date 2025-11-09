package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.OntologyRegistry;
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
    OntologyRegistry registry; // injection triggers producer at startup

    public OntologyStartupInitializer() {
        Log.debug("OntologyStartupInitializer constructed");
    }
}

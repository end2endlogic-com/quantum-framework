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

    @Inject
    com.e2eq.ontology.service.OntologyMetaService metaService;

    public OntologyStartupInitializer() {
        Log.debug("OntologyStartupInitializer constructed");
    }

    @jakarta.annotation.PostConstruct
    void backfillCanonicalPackPin() {
        // Upgraded deployments that recorded ontology meta before pack pinning
        // existed get their canonical hash backfilled here; fresh realms get
        // pinned by the reindexer. Never touches legacy drift-detection hashes.
        try {
            var registry = registryProvider.getRegistry();
            var tbox = new com.e2eq.ontology.core.OntologyRegistry.TBox(
                    registry.classes(), registry.properties(), registry.propertyChains());
            String source = metaService.getMeta().map(m -> m.getSource()).orElse(null);
            metaService.backfillCanonicalHashIfMissing(null,
                    tbox, com.e2eq.ontology.service.OntologyReindexer.packIdFromSource(source));
        } catch (RuntimeException e) {
            Log.debugf("Skipping canonical pack-pin backfill at startup: %s", e.getMessage());
        }
    }
}

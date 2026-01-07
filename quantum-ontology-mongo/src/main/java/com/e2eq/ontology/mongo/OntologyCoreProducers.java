package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.*;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import io.quarkus.arc.DefaultBean;
import io.quarkus.logging.Log;

/**
 * CDI Producer for OntologyRegistry.
 * <p>
 * This producer delegates to {@link TenantOntologyRegistryProvider} to support
 * multi-tenant (per-realm) ontology registries. Each realm (MongoDB database)
 * has its own TBox built from Morphia scan + YAML overlay.
 * </p>
 * <p>
 * The registry is request-scoped to ensure it resolves to the correct realm
 * based on the current SecurityContext. For long-lived operations outside
 * of request scope, inject {@link TenantOntologyRegistryProvider} directly
 * and call getRegistryForRealm(realm).
 * </p>
 */
@ApplicationScoped
public class OntologyCoreProducers {

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    /**
     * Produces an OntologyRegistry for the current request's realm.
     * <p>
     * The registry is resolved based on the current SecurityContext's realm.
     * Registries are cached per-realm in TenantOntologyRegistryProvider.
     * </p>
     * @return OntologyRegistry for the current realm
     */
    @Produces
    @DefaultBean
    @RequestScoped
    public OntologyRegistry ontologyRegistry() {
        Log.debug("OntologyCoreProducers: resolving OntologyRegistry for current realm");
        return registryProvider.getRegistry();
    }
}

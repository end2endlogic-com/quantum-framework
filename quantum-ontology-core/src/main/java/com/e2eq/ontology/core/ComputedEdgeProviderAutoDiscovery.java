package com.e2eq.ontology.core;

import com.e2eq.ontology.spi.OntologyEdgeProvider;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Automatically discovers and registers ComputedEdgeProvider implementations at startup.
 *
 * <p>This class bridges the gap between CDI bean discovery and the ComputedEdgeRegistry.
 * It observes all OntologyEdgeProvider beans (which ComputedEdgeProvider implements) and
 * registers those that are ComputedEdgeProvider instances into the registry.</p>
 *
 * <p>This enables automatic discovery of custom providers in application code without
 * requiring explicit registration calls.</p>
 *
 * <h2>How It Works</h2>
 * <ol>
 *   <li>At application startup, CDI discovers all @ApplicationScoped classes
 *       implementing OntologyEdgeProvider</li>
 *   <li>This class injects all those beans via Instance&lt;OntologyEdgeProvider&gt;</li>
 *   <li>For each bean that is a ComputedEdgeProvider, it registers it with
 *       the ComputedEdgeRegistry</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <p>Application code just needs to create an @ApplicationScoped class extending
 * ComputedEdgeProvider - this class will automatically discover and register it:</p>
 * <pre>{@code
 * @ApplicationScoped
 * public class MyEdgeProvider extends ComputedEdgeProvider<MyEntity> {
 *     // Implementation...
 * }
 * }</pre>
 */
@ApplicationScoped
@Startup
public class ComputedEdgeProviderAutoDiscovery {

    @Inject
    Instance<OntologyEdgeProvider> allProviders;

    @Inject
    ComputedEdgeRegistry registry;

    @PostConstruct
    void discoverAndRegister() {
        int registered = 0;
        int total = 0;

        for (OntologyEdgeProvider provider : allProviders) {
            total++;
            if (provider instanceof ComputedEdgeProvider<?> computedProvider) {
                try {
                    registry.register(computedProvider);
                    registered++;
                    Log.infof("Auto-registered ComputedEdgeProvider: %s (source: %s, predicate: %s)",
                        computedProvider.getProviderId(),
                        computedProvider.getSourceTypeName(),
                        computedProvider.getPredicate());
                } catch (Exception e) {
                    Log.warnf(e, "Failed to register ComputedEdgeProvider: %s",
                        computedProvider.getClass().getName());
                }
            }
        }

        Log.infof("ComputedEdgeProvider auto-discovery complete: registered %d of %d OntologyEdgeProvider beans",
            registered, total);
    }
}

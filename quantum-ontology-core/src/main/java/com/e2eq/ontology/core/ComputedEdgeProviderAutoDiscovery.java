package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

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
 * <b>Validation</b>
 * <p>During registration, this class validates that provider source types are properly
 * annotated with {@link OntologyClass}. If a provider's source type is not an ontology
 * class, a warning is logged because the provider's edges will not be processed when
 * entities are saved (since OntologyWriteHook requires @OntologyClass).</p>
 *
 * <b>How It Works</b>
 * <ol>
 *   <li>At application startup, CDI discovers all @ApplicationScoped classes
 *       implementing OntologyEdgeProvider</li>
 *   <li>This class injects all those beans via Instance&lt;OntologyEdgeProvider&gt;</li>
 *   <li>For each bean that is a ComputedEdgeProvider, it validates the source type
 *       and registers it with the ComputedEdgeRegistry</li>
 * </ol>
 *
 * <b>Usage</b>
 * <p>Application code just needs to create an @ApplicationScoped class extending
 * ComputedEdgeProvider - this class will automatically discover and register it:</p>
 * <pre>{@code
 * @ApplicationScoped
 * public class MyEdgeProvider extends ComputedEdgeProvider<MyEntity> {
 *     // Implementation...
 * }
 * }</pre>
 *
 * <p><strong>Important:</strong> The source entity type (MyEntity in the example above)
 * must be annotated with @OntologyClass for the provider to work correctly.</p>
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
        List<String> warnings = new ArrayList<>();

        for (OntologyEdgeProvider provider : allProviders) {
            total++;
            if (provider instanceof ComputedEdgeProvider<?> computedProvider) {
                try {
                    // Validate that the source type has @OntologyClass annotation
                    Class<?> sourceType = computedProvider.getSourceType();
                    boolean hasOntologyClass = sourceType.isAnnotationPresent(OntologyClass.class);

                    if (!hasOntologyClass) {
                        String warning = String.format(
                            "ComputedEdgeProvider '%s' has source type '%s' which is NOT annotated with @OntologyClass. " +
                            "This provider's edges will NOT be processed when entities are saved. " +
                            "Add @OntologyClass annotation to %s to enable edge computation.",
                            computedProvider.getProviderId(),
                            sourceType.getName(),
                            sourceType.getSimpleName()
                        );
                        warnings.add(warning);
                        Log.warnf(warning);
                    }

                    // Register the provider regardless (it might be used programmatically)
                    registry.register(computedProvider);
                    registered++;

                    if (hasOntologyClass) {
                        Log.infof("Auto-registered ComputedEdgeProvider: %s (source: %s, predicate: %s)",
                            computedProvider.getProviderId(),
                            computedProvider.getSourceTypeName(),
                            computedProvider.getPredicate());
                    } else {
                        Log.infof("Auto-registered ComputedEdgeProvider (with warnings): %s (source: %s, predicate: %s)",
                            computedProvider.getProviderId(),
                            computedProvider.getSourceTypeName(),
                            computedProvider.getPredicate());
                    }
                } catch (Exception e) {
                    Log.warnf(e, "Failed to register ComputedEdgeProvider: %s",
                        computedProvider.getClass().getName());
                }
            }
        }

        if (warnings.isEmpty()) {
            Log.infof("ComputedEdgeProvider auto-discovery complete: registered %d of %d OntologyEdgeProvider beans",
                registered, total);
        } else {
            Log.warnf("ComputedEdgeProvider auto-discovery complete: registered %d of %d OntologyEdgeProvider beans, " +
                "%d provider(s) have configuration issues (see warnings above)",
                registered, total, warnings.size());
        }
    }
}

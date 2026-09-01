package com.e2eq.framework.appregistration.runtime;

import com.e2eq.framework.appregistration.spi.ApplicationRegistrar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the configured {@link ApplicationRegistrar} from the beans on the
 * classpath. Mirrors how authentication providers are selected, deliberately:
 * one way to plug an implementation into the framework is easier to hold than
 * two.
 */
@ApplicationScoped
public class ApplicationRegistrarFactory {

    @Inject
    Instance<ApplicationRegistrar> registrars;

    public ApplicationRegistrarFactory() {
    }

    /** Construct over a fixed set of registrars, for use without a CDI container. */
    public ApplicationRegistrarFactory(Iterable<ApplicationRegistrar> registrars) {
        this.registrars = new FixedInstance<>(registrars);
    }

    public List<ApplicationRegistrar> getDiscoveredRegistrars() {
        List<ApplicationRegistrar> discovered = new ArrayList<>();
        for (ApplicationRegistrar registrar : registrars) {
            discovered.add(registrar);
        }
        return discovered;
    }

    public List<String> getDiscoveredNames() {
        List<String> names = new ArrayList<>();
        for (ApplicationRegistrar registrar : getDiscoveredRegistrars()) {
            names.add(registrar.getName() + " (" + registrar.getClass().getName() + ")");
        }
        return names;
    }

    /**
     * Find a registrar by its {@link ApplicationRegistrar#getName()} or by its
     * fully-qualified class name.
     *
     * <p>Both are accepted because both are natural things to write in a
     * properties file: the name is stable and readable, the class name is what
     * you have in front of you having just written the class.</p>
     */
    public Optional<ApplicationRegistrar> find(String selector) {
        if (selector == null || selector.isBlank()) {
            return Optional.empty();
        }
        String wanted = selector.trim();
        for (ApplicationRegistrar registrar : registrars) {
            if (wanted.equalsIgnoreCase(registrar.getName())
                    || wanted.equals(registrar.getClass().getName())) {
                return Optional.of(registrar);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve the configured registrar, or explain precisely why it could not be.
     *
     * <p>Configuration naming a registrar that is not on the classpath is an
     * error, never a silent skip: someone asked for registration and would
     * otherwise get a service that quietly never registers.</p>
     */
    public ApplicationRegistrar require(String selector) {
        return find(selector).orElseThrow(() -> new IllegalStateException(
                "quantum.application-registration.provider='" + selector + "' matches no "
                        + "ApplicationRegistrar on the classpath. Discovered: "
                        + (getDiscoveredNames().isEmpty() ? "(none)" : getDiscoveredNames())
                        + ". Add the implementation as a CDI bean, or set "
                        + "quantum.application-registration.enabled=false."));
    }
}

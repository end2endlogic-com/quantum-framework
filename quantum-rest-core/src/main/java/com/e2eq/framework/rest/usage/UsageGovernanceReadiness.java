package com.e2eq.framework.rest.usage;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import io.quarkus.runtime.Startup;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fails startup before ENFORCE can expose an unmapped or selector-inconsistent endpoint surface. */
@Startup
@ApplicationScoped
public class UsageGovernanceReadiness {

    @Inject
    UsageGovernanceConfig config;

    @Inject
    BeanManager beanManager;

    @PostConstruct
    void validate() {
        if (!config.active()) {
            return;
        }
        validateResourceClasses(config, resourceClasses(beanManager));
    }

    static void validateResourceClasses(UsageGovernanceConfig config, Set<Class<?>> resourceClasses) {
        Set<String> identities = new HashSet<>();
        List<String> unmapped = new ArrayList<>();
        for (Class<?> resourceClass : resourceClasses) {
            for (Method method : resourceClass.getMethods()) {
                if (!isHttpEndpoint(method)
                        || UsageGovernanceFilter.isPermitAllEndpoint(method, resourceClass)) {
                    continue;
                }
                try {
                    identities.add(UsageEndpointIdentityResolver
                            .resolveStatic(resourceClass, method).canonicalName());
                } catch (UsageEnforcementStateException exception) {
                    unmapped.add(resourceClass.getName() + "#" + method.getName());
                }
            }
        }
        if (!unmapped.isEmpty()) {
            throw new IllegalStateException(
                    "Active usage governance requires FunctionalMapping identity for authenticated endpoints: "
                            + String.join(", ", unmapped));
        }
        for (String selector : config.policy().endpointSelectors()) {
            if (!UsagePolicy.ALL_ENDPOINTS.equals(selector) && !identities.contains(selector)) {
                throw new IllegalStateException(
                        UsageGovernanceConfig.POLICY_ENDPOINTS
                                + " references a runtime endpoint identity that is not deployed: " + selector);
            }
        }
    }

    private static Set<Class<?>> resourceClasses(BeanManager beanManager) {
        Set<Class<?>> classes = new HashSet<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (hasPath(beanClass)) {
                classes.add(beanClass);
            }
        }
        return classes;
    }

    private static boolean hasPath(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (current.isAnnotationPresent(Path.class)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static boolean isHttpEndpoint(Method method) {
        for (var annotation : method.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(HttpMethod.class)) {
                return true;
            }
        }
        return false;
    }
}

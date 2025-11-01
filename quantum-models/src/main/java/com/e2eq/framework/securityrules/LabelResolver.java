package com.e2eq.framework.securityrules;

import java.util.Set;

/**
 * Pluggable SPI for resolving labels from various domain objects.
 * Implementations are discovered via CDI. Implement supports(type) to
 * indicate which entity types a resolver can handle.
 */
public interface LabelResolver {
    /**
     * @param type entity class
     * @return true if this resolver can resolve labels for the given type
     */
    boolean supports(Class<?> type);

    /**
     * Resolve labels for the given entity instance. Implementations should be
     * null-safe and return an empty set if no labels are found.
     */
    Set<String> resolveLabels(Object entity);
}

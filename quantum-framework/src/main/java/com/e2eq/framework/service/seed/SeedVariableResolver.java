package com.e2eq.framework.service.seed;

import java.util.Optional;

/**
 * SPI interface for resolving variables during seed string interpolation.
 *
 * <p>Implementations can be registered via Java ServiceLoader or CDI discovery
 * to provide custom variable resolution for seed data interpolation.</p>
 *
 * <p>Variables are referenced in seed data using the {@code {variableName}} syntax.
 * For example: {@code "admin@{tenantId}"} would resolve the {@code tenantId} variable.</p>
 *
 * <p>Built-in variables from SeedContext include:
 * <ul>
 *   <li>{@code realm} - the realm/database identifier</li>
 *   <li>{@code tenantId} - the tenant identifier</li>
 *   <li>{@code orgRefName} - the organization reference name</li>
 *   <li>{@code accountId} - the account identifier</li>
 *   <li>{@code ownerId} - the owner identifier</li>
 * </ul>
 * </p>
 *
 * @see StringInterpolationTransform
 */
public interface SeedVariableResolver {

    /**
     * Attempts to resolve a variable by name.
     *
     * @param variableName the name of the variable to resolve (without braces)
     * @param context the seed context providing tenant/realm information
     * @return an Optional containing the resolved value, or empty if this resolver
     *         does not handle this variable
     */
    Optional<String> resolve(String variableName, SeedContext context);

    /**
     * Returns the priority of this resolver. Higher priority resolvers are consulted first.
     * Default priority is 0. Built-in context resolver has priority -100 (lowest).
     *
     * @return the priority value
     */
    default int priority() {
        return 0;
    }
}

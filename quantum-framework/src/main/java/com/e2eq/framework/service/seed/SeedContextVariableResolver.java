package com.e2eq.framework.service.seed;

import java.util.Optional;

/**
 * Built-in variable resolver that provides variables from the SeedContext.
 *
 * <p>Supported variables:
 * <ul>
 *   <li>{@code realm} - the realm/database identifier</li>
 *   <li>{@code tenantId} - the tenant identifier</li>
 *   <li>{@code orgRefName} - the organization reference name</li>
 *   <li>{@code accountId} - the account identifier</li>
 *   <li>{@code ownerId} - the owner identifier</li>
 * </ul>
 *
 * <p>This resolver has the lowest priority (-100) so custom resolvers can override
 * context values if needed.
 */
public final class SeedContextVariableResolver implements SeedVariableResolver {

    public static final SeedContextVariableResolver INSTANCE = new SeedContextVariableResolver();

    private SeedContextVariableResolver() {
    }

    @Override
    public Optional<String> resolve(String variableName, SeedContext context) {
        if (variableName == null || context == null) {
            return Optional.empty();
        }

        return switch (variableName) {
            case "realm", "realmId" -> Optional.ofNullable(context.getRealm());
            case "tenantId" -> context.getTenantId();
            case "orgRefName" -> context.getOrgRefName();
            case "accountId" -> context.getAccountId();
            case "ownerId" -> context.getOwnerId();
            default -> Optional.empty();
        };
    }

    @Override
    public int priority() {
        // Lowest priority so custom resolvers can override
        return -100;
    }
}

# PrincipalContextPropertiesResolver SPI Design

## Overview

This document describes the design for an SPI that allows applications using the quantum-framework to add custom properties to `PrincipalContext`. These properties will be available for use in security policy rules via variable substitution.

## Problem Statement

Applications need to extend security context with domain-specific attributes:
- Associate ID linked to a UserProfile
- Territory assignments for data filtering
- Custom organizational hierarchies
- Dynamic access scopes computed from ontology relationships

Currently, there's no standardized way to:
1. Add custom properties to PrincipalContext during authentication
2. Make these properties available in policy rule filter strings (e.g., `${associateId}`)

## Design Goals

1. **Non-invasive**: Applications implement an SPI without modifying framework code
2. **CDI-based discovery**: Implementations are auto-discovered via `@ApplicationScoped`
3. **Access to full context**: Resolvers receive SecurityIdentity, credentials, realm info
4. **Integration with rules**: Custom properties available as `${varName}` in policy rules
5. **Type safety**: Support both String properties and typed objects for filters
6. **Performance**: Optional caching, lazy evaluation support

## Proposed SPI Interface

```java
package com.e2eq.framework.model.securityrules;

import io.quarkus.security.identity.SecurityIdentity;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for contributing custom properties to PrincipalContext.
 *
 * Implementations are discovered via CDI and invoked during
 * SecurityFilter.determinePrincipalContext() to enrich the
 * PrincipalContext with application-specific attributes.
 *
 * Properties are made available in policy rules via ${propertyName} syntax.
 */
public interface PrincipalContextPropertiesResolver {

    /**
     * Order of execution. Lower values execute first.
     * Use to control dependency between resolvers.
     * Default: 1000
     */
    default int priority() {
        return 1000;
    }

    /**
     * Resolve custom properties for the principal context.
     *
     * @param context Contains all available context for resolution:
     *                - SecurityIdentity (roles, principal)
     *                - CredentialUserIdPassword (optional, if found)
     *                - Realm information
     *                - HTTP headers from request
     *                - JWT claims (if JWT auth)
     *
     * @return Map of property name to value. Values can be:
     *         - String: Available as ${key} in rule filters
     *         - Collection<String>: Available as ${key} for $in queries
     *         - Object: Available in typed filter construction
     *
     *         Return empty map if no properties to contribute.
     */
    Map<String, Object> resolve(ResolutionContext context);

    /**
     * Context object providing access to all authentication/authorization data.
     */
    interface ResolutionContext {
        /** The authenticated security identity */
        SecurityIdentity getSecurityIdentity();

        /** Credentials from database, if found */
        Optional<CredentialUserIdPassword> getCredentials();

        /** The resolved realm (from X-Realm header or default) */
        String getRealm();

        /** The user ID (from JWT sub, identity principal, or credentials) */
        String getUserId();

        /** Subject ID if available (from JWT or credentials) */
        Optional<String> getSubjectId();

        /** Access to HTTP request headers */
        String getHeader(String name);

        /** Access to JWT claims (returns null if not JWT auth) */
        Object getJwtClaim(String claimName);

        /** The DataDomain being constructed */
        DataDomain getDataDomain();

        /** The DomainContext if available */
        Optional<DomainContext> getDomainContext();
    }
}
```

## PrincipalContext Changes

Add a `customProperties` map to PrincipalContext:

```java
public class PrincipalContext {
    // ... existing fields ...

    /** Custom properties contributed by PrincipalContextPropertiesResolver implementations */
    private final Map<String, Object> customProperties;

    // Builder addition
    public static class Builder {
        private Map<String, Object> customProperties = new HashMap<>();

        public Builder withCustomProperties(Map<String, Object> properties) {
            this.customProperties.putAll(properties);
            return this;
        }

        public Builder withCustomProperty(String key, Object value) {
            this.customProperties.put(key, value);
            return this;
        }
    }

    // Getter with @HostAccess.Export for Graal polyglot
    @HostAccess.Export
    public Map<String, Object> getCustomProperties() {
        return Collections.unmodifiableMap(customProperties);
    }

    @HostAccess.Export
    public Object getCustomProperty(String key) {
        return customProperties.get(key);
    }
}
```

## SecurityFilter Integration

In `SecurityFilter.determinePrincipalContext()`, after building the base context:

```java
@Inject
Instance<PrincipalContextPropertiesResolver> propertyResolvers;

protected PrincipalContext determinePrincipalContext(ContainerRequestContext requestContext) {
    // ... existing code to build baseContext ...

    PrincipalContext contextWithImpersonation = handleImpersonation(requestContext, baseContext);
    PrincipalContext contextWithRealm = applyRealmOverride(contextWithImpersonation, realm);

    // NEW: Apply custom property resolvers
    return applyCustomProperties(requestContext, contextWithRealm, ocreds);
}

private PrincipalContext applyCustomProperties(
        ContainerRequestContext requestContext,
        PrincipalContext context,
        Optional<CredentialUserIdPassword> credentials) {

    if (propertyResolvers == null || propertyResolvers.isUnsatisfied()) {
        return context;
    }

    // Build resolution context
    ResolutionContext resolutionContext = new ResolutionContextImpl(
        securityIdentity,
        credentials,
        context.getDefaultRealm(),
        context.getUserId(),
        jwt,
        requestContext,
        context.getDataDomain(),
        context.getDomainContext()
    );

    // Collect properties from all resolvers (sorted by priority)
    Map<String, Object> allProperties = new LinkedHashMap<>();

    List<PrincipalContextPropertiesResolver> sortedResolvers = new ArrayList<>();
    propertyResolvers.forEach(sortedResolvers::add);
    sortedResolvers.sort(Comparator.comparingInt(PrincipalContextPropertiesResolver::priority));

    for (PrincipalContextPropertiesResolver resolver : sortedResolvers) {
        try {
            Map<String, Object> props = resolver.resolve(resolutionContext);
            if (props != null && !props.isEmpty()) {
                allProperties.putAll(props);
                if (Log.isDebugEnabled()) {
                    Log.debugf("Resolver %s contributed properties: %s",
                        resolver.getClass().getSimpleName(), props.keySet());
                }
            }
        } catch (Exception e) {
            Log.warnf(e, "PrincipalContextPropertiesResolver %s failed; skipping",
                resolver.getClass().getName());
        }
    }

    if (allProperties.isEmpty()) {
        return context;
    }

    // Rebuild context with custom properties
    return new PrincipalContext.Builder()
            .withDefaultRealm(context.getDefaultRealm())
            .withDataDomain(context.getDataDomain())
            .withDomainContext(context.getDomainContext())
            .withUserId(context.getUserId())
            .withRoles(context.getRoles())
            .withScope(context.getScope())
            .withArea2RealmOverrides(context.getArea2RealmOverrides())
            .withDataDomainPolicy(context.getDataDomainPolicy())
            .withImpersonatedBySubject(context.getImpersonatedBySubject())
            .withImpersonatedByUserId(context.getImpersonatedByUserId())
            .withActingOnBehalfOfSubject(context.getActingOnBehalfOfSubject())
            .withActingOnBehalfOfUserId(context.getActingOnBehalfOfUserId())
            .withRealmOverrideActive(context.isRealmOverrideActive())
            .withOriginalDataDomain(context.getOriginalDataDomain())
            .withCustomProperties(allProperties)
            .build();
}
```

## MorphiaUtils Integration

Update `createStandardVariableMapFrom()` to include custom properties:

```java
public static Map<String, String> createStandardVariableMapFrom(
        PrincipalContext pcontext, ResourceContext rcontext) {

    Map<String, String> variableMap = new HashMap<>();

    // ... existing standard variables ...

    // Add custom properties (string values only for string map)
    if (pcontext.getCustomProperties() != null) {
        for (Map.Entry<String, Object> entry : pcontext.getCustomProperties().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                variableMap.put(entry.getKey(), (String) value);
            } else if (value != null) {
                variableMap.put(entry.getKey(), value.toString());
            }
        }
    }

    return variableMap;
}

public static VariableBundle buildVariableBundle(
        PrincipalContext pcontext,
        ResourceContext rcontext,
        Map<String, Object> extraObjects) {

    Map<String, String> strings = createStandardVariableMapFrom(pcontext, rcontext);
    Map<String, Object> objects = new HashMap<>(extraObjects);

    // Add custom properties to objects map (for typed filter construction)
    if (pcontext.getCustomProperties() != null) {
        objects.putAll(pcontext.getCustomProperties());
    }

    return new VariableBundle(strings, objects);
}
```

## Example Implementation

Application-side implementation for the Associate/Territory/Location use case:

```java
package com.myapp.security;

import com.e2eq.framework.model.securityrules.PrincipalContextPropertiesResolver;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class AssociatePropertiesResolver implements PrincipalContextPropertiesResolver {

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    AssociateRepo associateRepo;

    @Override
    public int priority() {
        return 100; // Run early
    }

    @Override
    public Map<String, Object> resolve(ResolutionContext context) {
        Map<String, Object> properties = new HashMap<>();

        // Skip for anonymous users
        if (context.getSecurityIdentity().isAnonymous()) {
            return properties;
        }

        String userProfileId = context.getSubjectId().orElse(context.getUserId());
        DataDomain dataDomain = context.getDataDomain();

        // Find Associate linked to this UserProfile via hasUser relationship
        // Associate.hasUser -> UserProfile (functional/1:1)
        Optional<String> associateId = edgeRepo.singleSrcIdByDst(
            dataDomain, "hasUser", userProfileId);

        if (associateId.isPresent()) {
            properties.put("associateId", associateId.get());

            // Get territories this associate can access
            Set<String> territoryIds = edgeRepo.dstIdsBySrc(
                dataDomain, "assignedToTerritory", associateId.get());
            properties.put("accessibleTerritoryIds", territoryIds);

            // Get locations this associate can see (via canSeeLocation)
            Set<String> locationIds = edgeRepo.dstIdsBySrc(
                dataDomain, "canSeeLocation", associateId.get());
            properties.put("accessibleLocationIds", locationIds);

            // Load associate for additional properties
            Optional<Associate> associate = associateRepo.findById(associateId.get());
            associate.ifPresent(a -> {
                properties.put("associateType", a.getAssociateType());
                properties.put("associateRegion", a.getRegion());
            });
        }

        return properties;
    }
}
```

## Using Custom Properties in Policy Rules

With the above resolver, rules can now reference:

```json
{
  "refName": "associate-location-access",
  "area": "sales",
  "functionalDomain": "location",
  "action": "VIEW",
  "effect": "ALLOW",
  "scope": "SCOPED",
  "roles": ["sales-rep", "sales-manager"],
  "andFilterString": "dataDomain.tenantId:${pTenantId}",
  "orFilterString": "_id:${accessibleLocationIds}"
}
```

Or for territory-based filtering:

```json
{
  "refName": "territory-order-access",
  "area": "sales",
  "functionalDomain": "order",
  "action": "LIST",
  "effect": "ALLOW",
  "scope": "SCOPED",
  "roles": ["sales-rep"],
  "andFilterString": "dataDomain.tenantId:${pTenantId} AND territoryId:${accessibleTerritoryIds}"
}
```

## Module Placement

| Component | Module |
|-----------|--------|
| `PrincipalContextPropertiesResolver` interface | `quantum-models` |
| `ResolutionContext` interface | `quantum-models` |
| `ResolutionContextImpl` class | `quantum-framework` |
| `PrincipalContext.customProperties` field | `quantum-models` |
| SecurityFilter integration | `quantum-framework` |
| MorphiaUtils integration | `quantum-morphia-repos` |

## Comparison with AccessListResolver

| Aspect | AccessListResolver | PrincipalContextPropertiesResolver |
|--------|-------------------|-----------------------------------|
| **When invoked** | During rule evaluation (lazy) | During authentication (eager) |
| **Context available** | PrincipalContext, ResourceContext, ModelClass | SecurityIdentity, Credentials, JWT, Headers |
| **Caching** | Per-request via RuleContext | Properties cached in PrincipalContext |
| **Use case** | Dynamic access lists based on resource being accessed | User-level properties constant across requests |
| **Performance** | Called per rule evaluation | Called once during auth |

## Security Considerations

1. **Property Isolation**: Custom properties should not override built-in properties (userId, roles, etc.)
2. **Error Handling**: Resolver failures should not block authentication
3. **Logging**: Property contributions logged at DEBUG level for audit
4. **Validation**: Resolver results should be validated before adding to context

## Migration Path

1. Applications currently computing properties in custom filters can migrate to this SPI
2. Existing `AccessListResolver` implementations remain unchanged
3. Both mechanisms can coexist - use `PrincipalContextPropertiesResolver` for user-level properties, `AccessListResolver` for resource-specific access lists

## Open Questions

1. Should custom properties be persisted/cached in the session?
2. Should there be a mechanism to refresh properties mid-session?
3. Should resolvers be able to short-circuit authentication (return error)?
4. Should we support async resolution for expensive computations?

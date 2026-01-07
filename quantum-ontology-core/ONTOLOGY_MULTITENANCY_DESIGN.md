# Ontology Multi-Tenancy Design Document

## Executive Summary

This document addresses making ontology TBox (schema) and ABox (edges) per-tenant, storing them the same way as all other data in the Quantum Framework—realm-scoped (database level) with DataDomain filtering (row level) within each realm.

**Key Decision**: The previous implementation treating TBox as "global" was a mistake. TBox should be **per-tenant**, not shared across realms.

**Approach**: No migration needed. The ontology will be rebuilt from data and configuration within each tenant's database at startup or on-demand.

### Related Documentation
- `quantum-docs/src/docs/ai-docs/ONTOLOGY_REALM_SCOPING_ANALYSIS.md` - Comprehensive realm scoping analysis
- `quantum-docs/src/docs/ai-docs/ONTOLOGY_DATADOMAIN_REFACTOR_PLAN.md` - DataDomain refactor plan
- `quantum-docs/src/docs/ai-docs/ONTOLOGY_DATADOMAIN_REFACTOR_IMPLEMENTATION_STATUS.md` - DataDomain status (COMPLETE)
- `quantum-docs/src/docs/asciidoc/user-guide/ontology.adoc` - User guide for ontology features

## Current Status

### DataDomain Refactor: ✅ COMPLETE
Per `ONTOLOGY_DATADOMAIN_REFACTOR_IMPLEMENTATION_STATUS.md`, all phases are complete:
- ✅ Phase 1: Model & Index Updates
- ✅ Phase 2: Repository Signature Changes  
- ✅ Phase 3: RuleContext Integration
- ✅ Phase 4: Edge Providers
- ✅ Phase 5: Materializer Updates
- ✅ Phase 6: Resource Layer & Query Rewriter
- ✅ Phase 7: Migration Script

### TBox Realm-Awareness: ✅ IMPLEMENTED

| Component | Previous Status | Current Status | Change Made |
|-----------|-----------------|----------------|-------------|
| `OntologyMeta` | GLOBAL singleton | ✅ Per-realm | Uses `getSecurityContextRealmId()` |
| `OntologyTBox` | GLOBAL | ✅ Per-realm | Uses `getSecurityContextRealmId()` |
| `OntologyRegistry` | Singleton | ✅ Request-scoped | Delegates to `TenantOntologyRegistryProvider` |
| All Ontology Repos | Use `defaultRealm` | ✅ Realm-aware | Updated `ds()` methods |
| `OntologyResource` | Direct injection | ✅ Provider-based | Injects `TenantOntologyRegistryProvider` |

## Problem Analysis

### Core Issue: Hardcoded `defaultRealm` in All Ontology Repositories

All ontology repositories use `defaultRealm` instead of `getSecurityContextRealmId()`:

```java
// Current pattern in ALL ontology repos:
private dev.morphia.Datastore ds() {
    return morphiaDataStoreWrapper.getDataStore(defaultRealm);  // ❌ Wrong
}
```

This contradicts the framework pattern used by other repos:
```java
// Pattern used by MorphiaRepo, RealmRepo, etc:
morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId())  // ✅ Correct
```

### Singleton OntologyRegistry

```java
@Produces
@Singleton  // ❌ One instance for all tenants
public OntologyRegistry ontologyRegistry() { ... }
```

This violates the documented ontology behavior which describes tenant-specific ontology use cases (see `ontology.adoc`).

## Proposed Solution

### Solution Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Request Flow                                      │
├─────────────────────────────────────────────────────────────────────────┤
│  HTTP Request → X-Realm Header → SecurityFilter → PrincipalContext      │
│                                                    (realm + dataDomain) │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Realm Selection Layer                                 │
├─────────────────────────────────────────────────────────────────────────┤
│  OntologyRegistryProvider → getSecurityContextRealmId()                 │
│                             ↓                                           │
│  OntologyRealmRepo → morphiaDataStoreWrapper.getDataStore(realmId)      │
│                       ↓                                                 │
│                   MongoDB Database per Realm                            │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    DataDomain Filtering Layer                            │
├─────────────────────────────────────────────────────────────────────────┤
│  Within each realm database, queries filter by:                         │
│  - dataDomain.orgRefName                                                │
│  - dataDomain.accountNum                                                │
│  - dataDomain.tenantId                                                  │
│  - dataDomain.dataSegment                                               │
└─────────────────────────────────────────────────────────────────────────┘
```

### Solution 1: Make All Ontology Repositories Realm-Aware

**Change Required**: All ontology repos should use security context realm instead of defaultRealm.

**Before**:
```java
private dev.morphia.Datastore ds() {
    return morphiaDataStoreWrapper.getDataStore(defaultRealm);
}
```

**After**:
```java
private dev.morphia.Datastore ds() {
    return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
}

// With override capability for system operations
private dev.morphia.Datastore ds(String realmOverride) {
    return morphiaDataStoreWrapper.getDataStore(
        realmOverride != null ? realmOverride : getSecurityContextRealmId()
    );
}
```

**Affected Files**:
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/repo/OntologyMetaRepo.java`
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/repo/OntologyTBoxRepo.java`
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/repo/TenantOntologyMetaRepo.java`
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/repo/TenantOntologyTBoxRepo.java`
- `quantum-ontology-mongo/src/main/java/com/e2eq/ontology/repo/OntologyEdgeRepo.java`

### Solution 2: Replace Singleton OntologyRegistry with Realm-Aware Provider

**Change Required**: Use `@RequestScoped` or contextual registry provider instead of `@Singleton`.

**Option A: Request-Scoped Registry (Simpler)**
```java
@Produces
@RequestScoped  // New: per-request, not singleton
public OntologyRegistry ontologyRegistry() {
    String realm = getSecurityContextRealmId();
    return ontologyRegistryProvider.getRegistryForRealm(realm);
}
```

**Option B: Realm-Aware Provider (More Explicit)**
```java
@ApplicationScoped
public class RealmAwareOntologyRegistryProvider {
    
    private final Map<String, OntologyRegistry> realmRegistries = new ConcurrentHashMap<>();
    
    @Inject
    OntologyTBoxRepo tboxRepo;  // Now realm-aware
    
    public OntologyRegistry getRegistry() {
        String realm = getSecurityContextRealmId();
        return realmRegistries.computeIfAbsent(realm, this::loadForRealm);
    }
    
    private OntologyRegistry loadForRealm(String realm) {
        // Load TBox from the specific realm's database
        Optional<OntologyTBox> tbox = tboxRepo.findLatest(realm);
        // ...
    }
    
    public void invalidateRealm(String realm) {
        realmRegistries.remove(realm);
    }
}
```

### Solution 3: Consolidate TBox Storage (Remove Redundancy)

**Current State**: Two parallel TBox storage mechanisms:
1. `OntologyTBox` / `OntologyMeta` (documented as "global")
2. `TenantOntologyTBox` / `TenantOntologyMeta` (with DataDomain fields)

**Proposal**: Use `OntologyTBox` and `OntologyMeta` for realm-level storage, keep DataDomain fields in `TenantOntology*` only for cases where multiple parties share a realm with different ontology needs.

**Recommended Pattern**:

| Use Case | Storage | Isolation |
|----------|---------|-----------|
| Different companies, separate databases | `OntologyTBox` in each realm | Realm (database) |
| Same database, different data owners | `TenantOntologyTBox` with DataDomain | DataDomain (row) |

### Solution 4: Update REST Resource to Use Provider

**Location**: `OntologyResource.java`

```java
@ApplicationScoped
@Path("ontology")
public class OntologyResource {

    @Inject
    RealmAwareOntologyRegistryProvider registryProvider;  // ✅ Provider, not direct registry
    
    @Inject
    OntologyEdgeRepo edgeRepo;  // ✅ Now realm-aware via getSecurityContextRealmId()
    
    @GET
    @Path("registry")
    public Response getTBox() {
        OntologyRegistry registry = registryProvider.getRegistry();  // ✅ Gets realm-specific
        // ...
    }
}
```

### Solution 5: Add Realm Parameter to Service Methods

**Location**: `OntologyMetaService.java`, `TenantOntologyService.java`, etc.

Services should accept explicit realm parameters or derive from security context:

```java
@ApplicationScoped
public class OntologyMetaService {
    
    public YamlObservationResult observeYaml(String realm, Optional<Path> yamlPath, String classpathFallback) {
        // Use realm for database selection
    }
    
    public void markApplied(String realm, String yamlHash, String tboxHash, Integer yamlVersion) {
        // Use realm for database selection
    }
}
```

### Solution 6: Update Startup Initialization

**Location**: `OntologyStartupInitializer.java`, `OntologyCoreProducers.java`

**Current**: Eagerly loads one global ontology at startup.

**Proposed**: Lazy load per-realm on first access, with optional preload configuration.

```java
@Startup
@ApplicationScoped
public class OntologyStartupInitializer {
    
    @Inject
    RealmAwareOntologyRegistryProvider registryProvider;
    
    @ConfigProperty(name = "quantum.ontology.preload.realms")
    Optional<List<String>> preloadRealms;
    
    @PostConstruct
    void init() {
        // Preload configured realms
        preloadRealms.ifPresent(realms -> 
            realms.forEach(registryProvider::preloadRealm)
        );
    }
}
```

## Data Model Changes

### Option A: Minimal Changes (Recommended for Phase 1)

Keep existing model classes, just change repository database selection:

```java
// OntologyTBox stays the same, stored in realm-specific database
@Entity(value = "ontology_tbox")
public class OntologyTBox extends UnversionedBaseModel {
    // Existing fields unchanged
}
```

### Option B: Add Realm Field (For Explicit Tracking)

Add realm field to models for explicit tracking:

```java
@Entity(value = "ontology_tbox")
public class OntologyTBox extends UnversionedBaseModel {
    // Existing fields...
    
    // New: explicit realm tracking (optional, since collection is per-realm anyway)
    private String realm;
}
```

### DataDomain Consideration

For cases where row-level ontology isolation is needed within a realm, `TenantOntologyTBox` with DataDomain remains appropriate:

```java
// For multi-party scenarios within single database
@Entity(value = "tenant_ontology_tbox")
public class TenantOntologyTBox extends UnversionedBaseModel {
    // DataDomain from base class provides row-level filtering
    // ...
}
```

## Implementation Plan

### Phase 1: Repository Realm-Awareness ✅ COMPLETED

1. ✅ Updated `OntologyEdgeRepo.ds()` to use `getSecurityContextRealmId()`
2. ✅ Updated `OntologyTBoxRepo.ds()` to use `getSecurityContextRealmId()`
3. ✅ Updated `OntologyMetaRepo.ds()` to use `getSecurityContextRealmId()`
4. ✅ Updated `TenantOntologyMetaRepo.ds()` to use `getSecurityContextRealmId()`
5. ✅ Updated `TenantOntologyTBoxRepo.ds()` to use `getSecurityContextRealmId()`
6. ✅ Added `ds(String realm)` overloads for explicit realm operations
7. ✅ Added `deleteAll()` methods to all repos for testing

### Phase 2: Registry Provider ✅ COMPLETED

1. ✅ Enhanced `TenantOntologyRegistryProvider` to be per-realm with caching
2. ✅ Updated `OntologyCoreProducers` to use `@RequestScoped` and delegate to provider
3. ✅ Updated `OntologyResource` to inject `TenantOntologyRegistryProvider`
4. ✅ Added realm management REST endpoints (`/realm/status`, `/realm/invalidate`, `/realm/rebuild`)
5. ✅ Added support for realm-specific YAML configuration (`ontology-{realm}.yaml`)

### Phase 3: Clean Up Redundant Models (Deferred)

Models are now properly documented with clear use cases:
- `OntologyTBox` / `OntologyMeta` - Per-realm TBox storage
- `TenantOntologyTBox` / `TenantOntologyMeta` - For DataDomain-level TBox within a realm (rare use case)

### Phase 4: Testing (Pending)

Note: Compilation passes. Some pre-existing test configuration issues (package mismatches) need to be addressed separately.

1. ⚠️ Existing integration tests have package organization issues
2. 🔄 Manual testing recommended before production deployment
3. ✅ Main code compiles successfully

## No Migration Required - Rebuild Approach

The ontology TBox is rebuilt from data and configuration within each tenant's database:

### How Rebuild Works

When a realm is first accessed after this change:

1. **Registry Provider** finds no cached registry for the realm
2. **Morphia Scan**: Scans `@OntologyClass`/`@OntologyProperty` annotations in the realm's datastore
3. **YAML Overlay**: Applies realm-specific or default YAML configuration
4. **Validation**: Validates the accumulated TBox
5. **Persistence**: Stores TBox hash and metadata in the realm's `ontology_meta` collection
6. **Caching**: Caches registry for subsequent requests

```java
// Conceptual rebuild flow per realm
private OntologyRegistry buildForRealm(String realm) {
    TBox accumulated = new TBox(Map.of(), Map.of(), List.of());
    
    // 1. Scan Morphia models in this realm
    MorphiaDatastore ds = datastoreWrapper.getDataStore(realm);
    MorphiaOntologyLoader loader = new MorphiaOntologyLoader(ds);
    accumulated = OntologyMerger.merge(accumulated, loader.loadTBox());
    
    // 2. Apply YAML overlay
    Optional<Path> yamlPath = resolveYamlPath(realm);
    if (yamlPath.isPresent()) {
        accumulated = OntologyMerger.merge(accumulated, yamlLoader.load(yamlPath.get()));
    }
    
    // 3. Validate and persist metadata
    OntologyValidator.validate(accumulated);
    String tboxHash = TBoxHasher.computeHash(accumulated);
    metaRepo.markApplied(realm, tboxHash);
    
    return new PersistedOntologyRegistry(accumulated, tboxHash);
}
```

### YAML Configuration Per Realm

Realm-specific YAML files can customize the ontology:

```
config/
  ontology.yaml                    # Default for all realms
  ontology-tenant-a.yaml           # Override for tenant-a
  ontology-tenant-b.yaml           # Override for tenant-b
```

### Backward Compatibility

1. Keep `defaultRealm` as fallback when security context is not available
2. Existing behavior unchanged for single-realm deployments

## Configuration Properties

New/updated configuration properties:

```properties
# Enable realm-aware ontology storage (default: false for backward compatibility)
quantum.ontology.realm-aware=true

# Preload ontologies for specific realms at startup (optional)
quantum.ontology.preload.realms=tenant-a,tenant-b

# Continue to use default realm as fallback when context unavailable
quantum.ontology.default-realm-fallback=true
```

## Summary of Changes Made

| File | Change Made | Status |
|------|-------------|--------|
| `OntologyEdgeRepo.java` | `ds()` → `getSecurityContextRealmId()` | ✅ |
| `OntologyTBoxRepo.java` | `ds()` → `getSecurityContextRealmId()` | ✅ |
| `OntologyMetaRepo.java` | `ds()` → `getSecurityContextRealmId()`, updated javadoc | ✅ |
| `TenantOntologyMetaRepo.java` | `ds()` → `getSecurityContextRealmId()` | ✅ |
| `TenantOntologyTBoxRepo.java` | `ds()` → `getSecurityContextRealmId()` | ✅ |
| `OntologyCoreProducers.java` | `@RequestScoped`, delegates to provider | ✅ |
| `TenantOntologyRegistryProvider.java` | Per-realm caching, rebuild logic | ✅ |
| `OntologyResource.java` | Inject provider, add realm mgmt endpoints | ✅ |
| `ONTOLOGY_MULTITENANCY_DESIGN.md` | This document | ✅ |

## Compatibility with Documented Ontology Features

All features documented in `quantum-docs/src/docs/asciidoc/user-guide/ontology.adoc` remain compatible:

| Feature | Documentation Reference | Impact |
|---------|-------------------------|--------|
| TBox (Classes, Properties, Chains) | "OntologyRegistry: Holds the TBox (terminology)" | ✅ Now per-realm, rebuilt from config |
| ABox (Edges) | "Each edge contains tenantId, src, predicate p, dst" | ✅ Already DataDomain-scoped |
| MorphiaOntologyLoader | "scans MorphiaDatastore.getMapper() to build OntologyRegistry" | ✅ Scans per-realm datastore |
| YamlOntologyLoader | "author your ontology in a simple YAML file" | ✅ Per-realm YAML overlay supported |
| OntologyMaterializer | "runs the Reasoner for an entity snapshot" | ✅ Uses realm-specific registry |
| ListQueryRewriter (hasEdge) | "hasEdge(predicate, dstIdOrVar)" | ✅ Already DataDomain-scoped |
| @OntologyProperty annotations | "Extracts explicit edges from fields/getters" | ✅ No change needed |
| Property chains | "if (A --p--> B) and (B --q--> C) then we infer (A --r--> C)" | ✅ Per-realm TBox has own rules |
| Transitive properties | "chains through intermediates" | ✅ Per-realm materialization |
| Inverse properties | "two properties point in opposite directions" | ✅ Per-realm materialization |
| REST API (/ontology/*) | "X-Realm header for ABox queries" | ✅ Extended to TBox endpoints |
| OntologyEdgeProvider SPI | "CDI beans implementing OntologyEdgeProvider" | ✅ No change needed |

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Performance impact of per-realm loading | Medium | Caching in provider, preload configuration |
| Security context not available | Medium | Fallback to defaultRealm with warning log |
| First-request latency per realm | Low | Optional preload configuration |

## Conclusion

The previous implementation treating TBox as "global" was a design mistake. This implementation corrects that, making ontology TBox per-tenant and aligning with the framework's multi-tenancy patterns where realm = database and dataDomain = row filter.

### Implementation Summary

1. ✅ **Repositories Updated**: All ontology repos now use `getSecurityContextRealmId()` instead of hardcoded `defaultRealm`
2. ✅ **Per-Realm Registry Provider**: `TenantOntologyRegistryProvider` caches registries per realm
3. ✅ **Request-Scoped Producer**: `OntologyCoreProducers` delegates to provider for each request
4. ✅ **REST API Enhanced**: New endpoints for realm cache management and forced rebuilds
5. ✅ **No Migration Required**: TBox is rebuilt from tenant data and configuration on first access
6. ✅ **Backward Compatible**: Falls back to `defaultRealm` when security context is unavailable

### New REST Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/ontology/realm/status` | GET | Get cached realm info |
| `/ontology/realm/invalidate` | POST | Invalidate cache for realm |
| `/ontology/realm/rebuild` | POST | Force rebuild TBox for realm |


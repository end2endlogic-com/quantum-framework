# TBox Persistence Implementation Guide

This document provides a concrete implementation plan for persisting the ontology TBox to MongoDB (Option 1 from the scalability analysis).

## Implementation Steps

### Step 1: Create OntologyTBox Entity

Create a new entity to store the TBox in MongoDB:

```java
package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.Field;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "ontology_tbox")
@Indexes({
    @Index(options = @IndexOptions(name = "idx_tbox_hash"), fields = { @Field("tboxHash") }),
    @Index(options = @IndexOptions(name = "idx_tbox_applied"), fields = { @Field("appliedAt") })
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyTBox extends UnversionedBaseModel {
    
    private String tboxHash;           // SHA-256 hash of TBox content
    private String yamlHash;           // Hash of source YAML (for correlation)
    private Date appliedAt;             // When this TBox was applied
    private String source;              // Source description (YAML path, etc.)
    
    // Serialized TBox content
    private Map<String, ClassDef> classes;
    private Map<String, PropertyDef> properties;
    private List<PropertyChainDef> chains;
    
    // Metadata
    private Integer version;            // Optional version number
    private String description;         // Optional description
    
    public OntologyTBox(TBox tbox, String tboxHash, String yamlHash, String source) {
        this.tboxHash = tboxHash;
        this.yamlHash = yamlHash;
        this.source = source;
        this.appliedAt = new Date();
        this.classes = tbox.classes();
        this.properties = tbox.properties();
        this.chains = tbox.propertyChains();
    }
    
    public TBox toTBox() {
        return new TBox(classes, properties, chains);
    }
    
    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }
    
    @Override
    public String bmFunctionalDomain() {
        return "tbox";
    }
}
```

### Step 2: Create Repository

```java
package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyTBox;
import dev.morphia.query.Query;
import dev.morphia.query.Sort;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class OntologyTBoxRepo extends MorphiaRepo<OntologyTBox> {
    
    /**
     * Get the most recently applied TBox
     */
    public Optional<OntologyTBox> findLatest() {
        Query<OntologyTBox> q = ds().find(OntologyTBox.class)
                .sort(Sort.descending("appliedAt"))
                .limit(1);
        return Optional.ofNullable(q.first());
    }
    
    /**
     * Find TBox by hash
     */
    public Optional<OntologyTBox> findByHash(String tboxHash) {
        Query<OntologyTBox> q = ds().find(OntologyTBox.class)
                .filter(Filters.eq("tboxHash", tboxHash));
        return Optional.ofNullable(q.first());
    }
    
    /**
     * Find TBox by YAML hash (for correlation)
     */
    public Optional<OntologyTBox> findByYamlHash(String yamlHash) {
        Query<OntologyTBox> q = ds().find(OntologyTBox.class)
                .filter(Filters.eq("yamlHash", yamlHash))
                .sort(Sort.descending("appliedAt"))
                .limit(1);
        return Optional.ofNullable(q.first());
    }
    
    private dev.morphia.Datastore ds() {
        return morphiaDataStore.getDataStore(defaultRealm);
    }
}
```

### Step 3: Create PersistedOntologyRegistry

```java
package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import java.util.*;

/**
 * OntologyRegistry implementation that loads from persisted TBox
 */
public final class PersistedOntologyRegistry implements OntologyRegistry {
    private final Map<String, ClassDef> classes;
    private final Map<String, PropertyDef> properties;
    private final List<PropertyChainDef> chains;
    private final String tboxHash;
    private final String yamlHash;
    
    public PersistedOntologyRegistry(TBox tbox, String tboxHash, String yamlHash) {
        this.classes = tbox.classes();
        this.properties = tbox.properties();
        this.chains = tbox.propertyChains();
        this.tboxHash = tboxHash;
        this.yamlHash = yamlHash;
    }
    
    @Override
    public Optional<ClassDef> classOf(String name) {
        return Optional.ofNullable(classes.get(name));
    }
    
    @Override
    public Optional<PropertyDef> propertyOf(String name) {
        return Optional.ofNullable(properties.get(name));
    }
    
    @Override
    public List<PropertyChainDef> propertyChains() {
        return chains;
    }
    
    @Override
    public Map<String, PropertyDef> properties() {
        return properties;
    }
    
    @Override
    public Map<String, ClassDef> classes() {
        return classes;
    }
    
    @Override
    public TBox getCurrentTBox() {
        return new TBox(classes, properties, chains);
    }
    
    @Override
    public String getHash() {
        return yamlHash != null ? yamlHash : tboxHash;
    }
    
    public String getTboxHash() {
        return tboxHash;
    }
}
```

### Step 4: Add TBox Hash Computation Utility

```java
package com.e2eq.ontology.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public class TBoxHasher {
    
    /**
     * Compute SHA-256 hash of TBox content for change detection
     */
    public static String computeHash(TBox tbox) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            
            // Hash classes
            tbox.classes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update(e.getValue().name().getBytes(StandardCharsets.UTF_8));
                    e.getValue().parents().forEach(p -> md.update(p.getBytes(StandardCharsets.UTF_8)));
                });
            
            // Hash properties
            tbox.properties().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                    md.update(e.getValue().name().getBytes(StandardCharsets.UTF_8));
                    e.getValue().domain().ifPresent(d -> md.update(d.getBytes(StandardCharsets.UTF_8)));
                    e.getValue().range().ifPresent(r -> md.update(r.getBytes(StandardCharsets.UTF_8)));
                });
            
            // Hash chains
            tbox.propertyChains().forEach(chain -> {
                chain.chain().forEach(c -> md.update(c.getBytes(StandardCharsets.UTF_8)));
                md.update(chain.implies().getBytes(StandardCharsets.UTF_8));
            });
            
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute TBox hash", e);
        }
    }
}
```

### Step 5: Modify OntologyCoreProducers

```java
// In OntologyCoreProducers.java - modify ontologyRegistry() method

@Inject
OntologyTBoxRepo tboxRepo;

@Produces
@DefaultBean
@Singleton
public OntologyRegistry ontologyRegistry() {
    Log.info("OntologyCoreProducers: building OntologyRegistry at startup...");
    
    // 1. Try to load from persisted TBox first
    Optional<OntologyTBox> persistedTBox = tboxRepo.findLatest();
    String currentYamlHash = "unknown";
    boolean needsReindex = false;
    
    // 2. Check if YAML has changed
    try {
        var yamlResult = metaService.observeYaml(resolveYamlPath(), "/ontology.yaml");
        currentYamlHash = yamlResult.currentHash();
        needsReindex = metaService.getMeta().map(m -> m.isReindexRequired()).orElse(false);
        
        // If we have a persisted TBox and YAML hasn't changed, use it
        if (persistedTBox.isPresent()) {
            String persistedYamlHash = persistedTBox.get().getYamlHash();
            if (persistedYamlHash != null && persistedYamlHash.equals(currentYamlHash)) {
                Log.info("Loading TBox from persistence (hash: " + persistedTBox.get().getTboxHash() + ")");
                return new PersistedOntologyRegistry(
                    persistedTBox.get().toTBox(),
                    persistedTBox.get().getTboxHash(),
                    persistedTBox.get().getYamlHash()
                );
            } else {
                Log.info("YAML changed, rebuilding TBox (old hash: " + persistedYamlHash + ", new: " + currentYamlHash + ")");
            }
        }
    } catch (Throwable t) {
        Log.warn("Failed to check YAML hash, will rebuild TBox: " + t.getMessage());
    }
    
    // 3. Rebuild TBox (existing logic)
    TBox accumulated = new TBox(Map.of(), Map.of(), List.of());
    
    // ... existing annotation scanning logic ...
    // ... existing Morphia loading logic ...
    // ... existing YAML loading logic ...
    
    // 4. Compute TBox hash and persist
    String tboxHash = TBoxHasher.computeHash(accumulated);
    String source = resolveYamlPath()
        .map(Path::toString)
        .orElse("/ontology.yaml");
    
    // Check if this TBox already exists
    Optional<OntologyTBox> existing = tboxRepo.findByHash(tboxHash);
    if (existing.isEmpty()) {
        OntologyTBox newTBox = new OntologyTBox(accumulated, tboxHash, currentYamlHash, source);
        tboxRepo.save(newTBox);
        Log.info("Persisted new TBox (hash: " + tboxHash + ")");
    } else {
        Log.info("TBox already persisted (hash: " + tboxHash + ")");
    }
    
    // 5. Return registry
    return new YamlBackedOntologyRegistry(accumulated, currentYamlHash, needsReindex);
}
```

### Step 6: Add Migration (Optional)

If you want to persist existing TBox on first run:

```java
package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.TBoxHasher;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OntologyTBoxMigration {
    
    @Inject
    OntologyTBoxRepo tboxRepo;
    
    @Inject
    OntologyRegistry registry;
    
    /**
     * Migrate existing in-memory TBox to persistence
     * Call this after OntologyRegistry is initialized
     */
    public void migrateIfNeeded() {
        if (tboxRepo.findLatest().isEmpty()) {
            Log.info("No persisted TBox found, migrating current TBox...");
            var tbox = registry.getCurrentTBox();
            String tboxHash = TBoxHasher.computeHash(tbox);
            String yamlHash = registry.getHash();
            
            OntologyTBox persisted = new OntologyTBox(
                tbox,
                tboxHash,
                yamlHash,
                "migration"
            );
            tboxRepo.save(persisted);
            Log.info("TBox migration complete (hash: " + tboxHash + ")");
        }
    }
}
```

## Testing Strategy

1. **Unit Tests**:
   - Test TBox hash computation (same TBox = same hash)
   - Test persistence/retrieval
   - Test YAML change detection

2. **Integration Tests**:
   - Test startup with persisted TBox
   - Test startup without persisted TBox (rebuild)
   - Test YAML change triggers rebuild

3. **Performance Tests**:
   - Measure startup time with/without persistence
   - Measure TBox load time from DB vs rebuild

## Rollout Plan

1. **Phase 1**: Deploy code, but don't use persisted TBox yet (feature flag)
2. **Phase 2**: Enable persistence, monitor for issues
3. **Phase 3**: Remove feature flag, make persistence default

## Configuration

Add optional config to control behavior:

```properties
# Enable TBox persistence
quantum.ontology.tbox.persist=true

# Force rebuild even if persisted TBox exists
quantum.ontology.tbox.force-rebuild=false
```

## Benefits After Implementation

- ✅ **Faster startup**: Load from DB (milliseconds) vs rebuild (seconds)
- ✅ **Version history**: Can query historical TBox versions
- ✅ **Consistency**: Same TBox across instances
- ✅ **Auditability**: Track when TBox changed and why
- ✅ **Rollback**: Can revert to previous TBox version if needed


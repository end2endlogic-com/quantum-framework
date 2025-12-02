package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.*;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.service.OntologyMetaService;
import dev.morphia.MorphiaDatastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.quarkus.arc.DefaultBean;
import io.quarkus.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class OntologyCoreProducers {

    @Inject
    MorphiaDatastore morphiaDatastore;

    @Inject
    OntologyMetaService metaService;
    
    @Inject
    OntologyTBoxRepo tboxRepo;

    @Produces
    @DefaultBean
    @Singleton
    public OntologyRegistry ontologyRegistry() {
        Log.info("OntologyCoreProducers: building OntologyRegistry at startup...");
        
        // Check configuration for TBox persistence feature flag
        Config config = ConfigProvider.getConfig();
        boolean persistTBox = config.getOptionalValue("quantum.ontology.tbox.persist", Boolean.class)
                .orElse(true); // Default to enabled
        boolean forceRebuild = config.getOptionalValue("quantum.ontology.tbox.force-rebuild", Boolean.class)
                .orElse(false);
        
        // 1. Try to load from persisted TBox first (if persistence is enabled)
        if (persistTBox && !forceRebuild) {
            try {
                Optional<OntologyTBox> persistedTBox = tboxRepo.findLatest();
                if (persistedTBox.isPresent()) {
                    // Check if YAML has changed
                    Optional<Path> yamlPath = resolveYamlPath();
                    var yamlResult = metaService.observeYaml(yamlPath, "/ontology.yaml");
                    String currentYamlHash = yamlResult.currentHash();
                    String persistedYamlHash = persistedTBox.get().getYamlHash();
                    
                    // If YAML hash matches, use persisted TBox
                    if (persistedYamlHash != null && persistedYamlHash.equals(currentYamlHash)) {
                        boolean needsReindex = metaService.getMeta()
                                .map(m -> m.isReindexRequired())
                                .orElse(false);
                        Log.infof("Loading TBox from persistence (hash: %s, yamlHash: %s)", 
                                persistedTBox.get().getTboxHash(), persistedYamlHash);
                        return new PersistedOntologyRegistry(
                            persistedTBox.get().toTBox(),
                            persistedTBox.get().getTboxHash(),
                            persistedTBox.get().getYamlHash(),
                            needsReindex
                        );
                    } else {
                        Log.infof("YAML changed, rebuilding TBox (old yamlHash: %s, new: %s)", 
                                persistedYamlHash, currentYamlHash);
                    }
                }
            } catch (Throwable t) {
                Log.warn("Failed to load persisted TBox, will rebuild: " + t.getMessage(), t);
            }
        }
        
        // 2. Rebuild TBox from sources (existing logic)
        TBox accumulated = new TBox(Map.of(), Map.of(), List.of());

        // 2a) Optional: scan configured packages for @OntologyClass/@OntologyProperty
        try {
            Optional<String> scan = config.getOptionalValue("quantum.ontology.scan.packages", String.class);
            if (scan.isPresent() && !scan.get().isBlank()) {
                List<String> pkgs = Arrays.stream(scan.get().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (!pkgs.isEmpty()) {
                    AnnotationOntologyLoader annoLoader = new AnnotationOntologyLoader();
                    TBox annoTBox = annoLoader.loadFromPackages(pkgs);
                    accumulated = OntologyMerger.merge(accumulated, annoTBox);
                }
            }
        } catch (Throwable ignored) { }

        // 2b) Merge in Morphia-derived TBox if available
        try {
            if (morphiaDatastore != null && morphiaDatastore.getMapper() != null) {
                MorphiaOntologyLoader loader = new MorphiaOntologyLoader(morphiaDatastore);
                TBox base = loader.loadTBox();
                accumulated = OntologyMerger.merge(accumulated, base);
            }
        } catch (Throwable ignored) { }

        // 2c) YAML overlay from configured or conventional locations and compute/store hash
        Optional<Path> path = Optional.empty();
        String currentHash = "unknown";
        boolean needsReindex = false;
        try {
            YamlOntologyLoader yaml = new YamlOntologyLoader();
            path = resolveYamlPath();
            TBox overlay = null;
            if (path.isPresent() && Files.exists(path.get())) {
                overlay = yaml.loadFromPath(path.get());
            } else {
                try { overlay = yaml.loadFromClasspath("/ontology.yaml"); } catch (Exception ignored) { }
            }
            if (overlay != null) {
                accumulated = OntologyMerger.merge(accumulated, overlay);
            }
            var result = metaService.observeYaml(path, "/ontology.yaml");
            currentHash = result.currentHash();
            needsReindex = metaService.getMeta().map(m -> m.isReindexRequired()).orElse(false);
        } catch (Throwable t) {
            Log.warn("Failed to observe/update ontology meta from YAML: " + t.getMessage());
        }

        // 3. Validate final TBox
        try { 
            OntologyValidator.validate(accumulated); 
        } catch (Exception e) { 
            Log.warn("TBox validation failed: " + e.getMessage());
            // In prod you may want to fail fast
        }
        
        // 4. Persist TBox if persistence is enabled
        if (persistTBox) {
            try {
                String tboxHash = TBoxHasher.computeHash(accumulated);
                String source = path.map(Path::toString).orElse("/ontology.yaml");
                
                // Check if this TBox already exists
                Optional<OntologyTBox> existing = tboxRepo.findByHash(tboxHash);
                if (existing.isEmpty()) {
                    OntologyTBox newTBox = new OntologyTBox(accumulated, tboxHash, currentHash, source);
                    tboxRepo.save(newTBox);
                    Log.infof("Persisted new TBox (hash: %s, yamlHash: %s)", tboxHash, currentHash);
                } else {
                    Log.debugf("TBox already persisted (hash: %s)", tboxHash);
                }
            } catch (Throwable t) {
                Log.warn("Failed to persist TBox: " + t.getMessage(), t);
                // Continue without persistence
            }
        }
        
        // 5. Return registry
        return new YamlBackedOntologyRegistry(accumulated, currentHash, needsReindex);
    }

    private Optional<Path> resolveYamlPath() {
        String sys = System.getProperty("ontology.yaml.path");
        if (sys != null && !sys.isBlank()) return Optional.of(Path.of(sys));
        String env = System.getenv("ONTOLOGY_YAML");
        if (env != null && !env.isBlank()) return Optional.of(Path.of(env));
        Path conventional = Path.of("config", "ontology.yaml");
        if (Files.exists(conventional)) return Optional.of(conventional);
        return Optional.empty();
    }
}

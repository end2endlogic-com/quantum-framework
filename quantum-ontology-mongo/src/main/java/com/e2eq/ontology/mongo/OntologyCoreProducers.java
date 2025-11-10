package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.*;
import com.e2eq.ontology.core.OntologyRegistry.*;
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

    @Produces
    @DefaultBean
    @Singleton
    public OntologyRegistry ontologyRegistry() {
        Log.info("OntologyCoreProducers: building OntologyRegistry at startup...");
        // Start with empty
        TBox accumulated = new TBox(Map.of(), Map.of(), List.of());

        // 1) Optional: scan configured packages for @OntologyClass/@OntologyProperty
        try {
            Config config = ConfigProvider.getConfig();
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

        // 2) Merge in Morphia-derived TBox if available
        try {
            if (morphiaDatastore != null && morphiaDatastore.getMapper() != null) {
                MorphiaOntologyLoader loader = new MorphiaOntologyLoader(morphiaDatastore);
                TBox base = loader.loadTBox();
                accumulated = OntologyMerger.merge(accumulated, base);
            }
        } catch (Throwable ignored) { }

        // 3) YAML overlay from configured or conventional locations and compute/store hash
        Optional<Path> path = Optional.empty();
        try {
            YamlOntologyLoader yaml = new YamlOntologyLoader();
            path = resolveYamlPath();
            if (path.isPresent() && Files.exists(path.get())) {
                TBox overlay = yaml.loadFromPath(path.get());
                accumulated = OntologyMerger.merge(accumulated, overlay);
            } else {
                try {
                    TBox overlay = yaml.loadFromClasspath("/ontology.yaml");
                    accumulated = OntologyMerger.merge(accumulated, overlay);
                } catch (Exception ignored) { }
            }
        } catch (Throwable ignored) { }

        // Update ontology meta/hash and mark reindexRequired if changed
        try {
            metaService.updateFromYaml(path, "/ontology.yaml");
        } catch (Throwable t) {
            Log.warn("Failed to update ontology meta from YAML: " + t.getMessage());
        }

        // Validate final TBox
        try { OntologyValidator.validate(accumulated); } catch (Exception e) { /* in prod you may want to fail fast */ }
        return new InMemoryOntologyRegistry(accumulated);
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

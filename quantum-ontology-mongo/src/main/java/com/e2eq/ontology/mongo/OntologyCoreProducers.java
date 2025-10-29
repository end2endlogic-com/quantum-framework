package com.e2eq.ontology.mongo;

import com.e2eq.ontology.core.*;
import com.e2eq.ontology.core.OntologyRegistry.*;
import dev.morphia.MorphiaDatastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@ApplicationScoped
public class OntologyCoreProducers {

    @Inject
    MorphiaDatastore morphiaDatastore;

    @Produces
    @Singleton
    public OntologyRegistry ontologyRegistry() {
        // Base TBox from Morphia annotations, if possible
        TBox base = new TBox(Map.of(), Map.of(), List.of());
        try {
            if (morphiaDatastore != null && morphiaDatastore.getMapper() != null) {
                MorphiaOntologyLoader loader = new MorphiaOntologyLoader(morphiaDatastore);
                base = loader.loadTBox();
            }
        } catch (Throwable ignored) { }

        // YAML overlay from configured or conventional locations
        TBox overlay = null;
        try {
            YamlOntologyLoader yaml = new YamlOntologyLoader();
            Optional<Path> path = resolveYamlPath();
            if (path.isPresent() && Files.exists(path.get())) {
                overlay = yaml.loadFromPath(path.get());
            } else {
                try {
                    overlay = yaml.loadFromClasspath("/ontology.yaml");
                } catch (Exception ignored) { }
            }
        } catch (Throwable ignored) { }

        TBox merged = (overlay != null) ? OntologyMerger.merge(base, overlay) : base;
        // Validate final TBox
        try { OntologyValidator.validate(merged); } catch (Exception e) { /* in prod you may want to fail fast */ }
        return new InMemoryOntologyRegistry(merged);
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

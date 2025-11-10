package com.e2eq.ontology.service;

import com.e2eq.ontology.model.OntologyMeta;
import com.e2eq.ontology.repo.OntologyMetaRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@ApplicationScoped
public class OntologyMetaService {

    @Inject
    OntologyMetaRepo metaRepo;

    public Optional<OntologyMeta> getMeta() { return metaRepo.getSingleton(); }

    /**
     * Observe current YAML on disk or classpath, compute its SHA-256, and update metadata reindex flag
     * if it differs from the last applied yamlHash. Does not overwrite yamlHash.
     * Returns a pair-like holder with the metadata and the computed current hash.
     */
    public Result observeYaml(Optional<Path> path, String classpathFallback) {
        try {
            Observed observed = readYamlBytes(path, classpathFallback);
            String currentHash = sha256(observed.bytes);
            boolean changed = metaRepo.getSingleton()
                    .map(m -> m.getYamlHash() == null || !m.getYamlHash().equals(currentHash))
                    .orElse(true);
            OntologyMeta meta = metaRepo.upsertObservation(null, observed.source, changed);
            if (changed) {
                Log.infof("Ontology YAML changed; new hash=%s, source=%s. Marking reindexRequired=true.", currentHash, observed.source);
            }
            return new Result(meta, currentHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute ontology YAML hash", e);
        }
    }

    public void markApplied(String appliedHash) {
        metaRepo.markApplied(appliedHash);
    }

    public void clearReindexRequired() {
        metaRepo.getSingleton().ifPresent(m -> {
            m.setReindexRequired(false);
            metaRepo.saveSingleton(m);
        });
    }

    private Observed readYamlBytes(Optional<Path> path, String classpathFallback) throws Exception {
        byte[] bytes;
        String source;
        if (path.isPresent() && Files.exists(path.get())) {
            bytes = Files.readAllBytes(path.get());
            source = path.get().toAbsolutePath().toString();
        } else {
            try (InputStream in = getClass().getResourceAsStream(classpathFallback)) {
                if (in == null) {
                    Log.warn("OntologyMetaService: YAML not found at " + classpathFallback);
                    bytes = new byte[0];
                    source = "<none>";
                } else {
                    bytes = in.readAllBytes();
                    source = classpathFallback;
                }
            }
        }
        return new Observed(bytes, source);
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        return HexFormat.of().formatHex(md.digest());
    }

    public record Result(OntologyMeta meta, String currentHash) {}
    private record Observed(byte[] bytes, String source) {}
}

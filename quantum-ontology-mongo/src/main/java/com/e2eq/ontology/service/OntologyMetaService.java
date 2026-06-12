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

    public Optional<OntologyMeta> getMeta() { return getMeta(null); }
    public Optional<OntologyMeta> getMeta(String realmId) {
        if (realmId != null) return metaRepo.getSingleton(realmId);
        return metaRepo.getSingleton();
    }

    /**
     * Observe current YAML on disk or classpath, compute its SHA-256, and update metadata reindex flag
     * if it differs from the last applied yamlHash. Does not overwrite yamlHash.
     * Returns a pair-like holder with the metadata and the computed current hash.
     */
    public Result observeYaml(Optional<Path> path, String classpathFallback) {
        return observeYaml(null, path, classpathFallback);
    }

    public Result observeYaml(String realmId, Optional<Path> path, String classpathFallback) {
        try {
            Observed observed = readYamlBytes(path, classpathFallback);
            String currentHash = sha256(observed.bytes);
            boolean changed = getMeta(realmId)
                    .map(m -> m.getYamlHash() == null || !m.getYamlHash().equals(currentHash))
                    .orElse(true);
            OntologyMeta meta;
            if (realmId != null) {
                meta = metaRepo.upsertObservation(realmId, null, observed.source, changed);
            } else {
                meta = metaRepo.upsertObservation(null, observed.source, changed);
            }

            if (changed) {
                Log.infof("Ontology YAML changed; new hash=%s, source=%s. Marking reindexRequired=true.", currentHash, observed.source);
            }
            return new Result(meta, currentHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute ontology YAML hash", e);
        }
    }

    public void markApplied(String yamlHash, String tboxHash, Integer yamlVersion) {
        metaRepo.markApplied(yamlHash, tboxHash, yamlVersion);
    }

    public void markApplied(String realmId, String yamlHash, String tboxHash, Integer yamlVersion) {
        metaRepo.markApplied(realmId, yamlHash, tboxHash, yamlVersion);
    }

    /**
     * Record the pack pin for a realm: pack identity plus the cross-stack
     * canonical TBox hash (CanonicalTBoxHasher / HASH_SPEC.md), so a running
     * realm can prove which vocabulary version it enforces.
     */
    public void recordPackPin(String realmId, com.e2eq.ontology.core.OntologyRegistry.TBox tbox,
                              String packId, Integer packVersion) {
        int version = packVersion != null ? packVersion : 1;
        String canonicalHash = com.e2eq.ontology.core.CanonicalTBoxHasher.hashTBox(
                tbox,
                new com.e2eq.ontology.core.CanonicalTBoxHasher.PackMetadata(
                        packId, packId, version, "closed", null, null));
        metaRepo.recordPackPin(realmId, packId, String.valueOf(version), canonicalHash);
        Log.infof("Recorded ontology pack pin for realm %s: %s@%s canonicalTboxHash=%s",
                realmId, packId, version, canonicalHash);
    }

    public void clearReindexRequired() {
        // Use atomic update instead of read-modify-write
        metaRepo.getSingleton().ifPresent(m -> 
            metaRepo.upsertObservation(m.getYamlVersion(), m.getSource(), false)
        );
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

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

    public OntologyMeta updateFromYaml(Optional<Path> path, String classpathFallback) {
        try {
            byte[] bytes;
            String source;
            Integer version = null;
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
            String hash = sha256(bytes);
            boolean changed = metaRepo.getSingleton()
                    .map(m -> m.getYamlHash() == null || !m.getYamlHash().equals(hash))
                    .orElse(true);
            OntologyMeta meta = metaRepo.upsert(hash, version, source, changed);
            if (changed) {
                Log.infof("Ontology YAML changed; new hash=%s, source=%s. Marking reindexRequired=true.", hash, source);
            }
            return meta;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute ontology YAML hash", e);
        }
    }

    public void clearReindexRequired() {
        metaRepo.getSingleton().ifPresent(m -> {
            m.setReindexRequired(false);
            metaRepo.saveSingleton(m);
        });
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        return HexFormat.of().formatHex(md.digest());
    }
}

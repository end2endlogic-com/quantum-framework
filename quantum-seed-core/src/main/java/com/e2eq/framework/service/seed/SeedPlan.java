package com.e2eq.framework.service.seed;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

/** Persist this value before execution. It contains fingerprints, never dataset contents. */
@RegisterForReflection
public record SeedPlan(int schemaVersion, String contextSha256, List<Pack> packs) {
    public SeedPlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported seed plan version");
        requireHash(contextSha256);
        packs = List.copyOf(packs);
    }

    @RegisterForReflection
    public record Pack(String name, String version, String manifestSha256, List<Dataset> datasets) {
        public Pack {
            if (name == null || name.isBlank() || version == null || version.isBlank())
                throw new IllegalArgumentException("Seed plan requires exact pack identity");
            requireHash(manifestSha256);
            datasets = List.copyOf(datasets);
        }
    }

    @RegisterForReflection
    public record Dataset(String file, String sha256) {
        public Dataset {
            Objects.requireNonNull(file, "file");
            requireHash(sha256);
        }
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Seed plan requires SHA-256 fingerprints");
    }
}

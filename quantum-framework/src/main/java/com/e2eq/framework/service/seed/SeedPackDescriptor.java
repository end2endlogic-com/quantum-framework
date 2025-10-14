package com.e2eq.framework.service.seed;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a concrete, loadable seed pack discovered by a {@link SeedSource}.
 */
public final class SeedPackDescriptor {

    private final SeedSource source;
    private final SeedPackManifest manifest;
    private final Path manifestPath;

    public SeedPackDescriptor(SeedSource source, SeedPackManifest manifest, Path manifestPath) {
        this.source = Objects.requireNonNull(source, "source");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
    }

    public SeedSource getSource() {
        return source;
    }

    public SeedPackManifest getManifest() {
        return manifest;
    }

    public Path getManifestPath() {
        return manifestPath;
    }

    public String identity() {
        return manifest.getSeedPack() + "@" + manifest.getVersion();
    }
}

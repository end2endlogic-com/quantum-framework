package com.e2eq.framework.service.seed;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Represents a concrete, loadable seed pack discovered by a {@link SeedSource}.
 */
public final class SeedPackDescriptor {

    private final SeedSource source;
    private final SeedPackManifest manifest;
    private final Path manifestPath;
    /**
     * Exact resource URL of the manifest on the classpath (jar:file:..., or file:...),
     * when available. May be null for file-based sources that do not use classpath.
     */
    private final URL manifestUrl;

    /**
     * Backward-compatible constructor for sources that only provide a filesystem path.
     */
    public SeedPackDescriptor(SeedSource source, SeedPackManifest manifest, Path manifestPath) {
        this(source, manifest, manifestPath, null);
    }

    public SeedPackDescriptor(SeedSource source, SeedPackManifest manifest, Path manifestPath, URL manifestUrl) {
        this.source = Objects.requireNonNull(source, "source");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        this.manifestUrl = manifestUrl; // may be null
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

    public URL getManifestUrl() {
        return manifestUrl;
    }

    public String identity() {
        return manifest.getSeedPack() + "@" + manifest.getVersion();
    }
}

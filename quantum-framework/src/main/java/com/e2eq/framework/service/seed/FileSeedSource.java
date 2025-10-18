package com.e2eq.framework.service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads seed packs from a directory structure on the filesystem or classpath.
 */
public final class FileSeedSource implements SeedSource, SchemeAware {

    private final String id;
    private final Path root;
    private final String manifestFileName;

    public FileSeedSource(String id, Path root) {
        this(id, root, "manifest.yaml");
    }

    public FileSeedSource(String id, Path root, String manifestFileName) {
        this.id = Objects.requireNonNull(id, "id");
        this.root = Objects.requireNonNull(root, "root");
        this.manifestFileName = Objects.requireNonNull(manifestFileName, "manifestFileName");
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public List<SeedPackDescriptor> loadSeedPacks(SeedContext context) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        List<SeedPackDescriptor> descriptors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(manifestFileName))
                    .forEach(path -> {
                        try (InputStream in = Files.newInputStream(path)) {
                            String description = path.toAbsolutePath().toString();
                            SeedPackManifest manifest = SeedPackManifest.load(in, description);
                            descriptors.add(new SeedPackDescriptor(this, manifest, path));
                        } catch (IOException e) {
                            throw new SeedSourceIOException("Failed to read seed manifest at " + path, e);
                        }
                    });
        } catch (SeedSourceIOException e) {
            throw new IOException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IOException("Failed to scan seed packs under " + root, e);
        }
        return descriptors;
    }

    @Override
    public InputStream openDataset(SeedPackDescriptor descriptor, String relativePath) throws IOException {
        Path datasetPath = descriptor.getManifestPath().getParent().resolve(relativePath).normalize();
        if (!datasetPath.startsWith(root)) {
            throw new IOException("Dataset path " + datasetPath + " is outside seed source root " + root);
        }
        return Files.newInputStream(datasetPath);
    }

    // SchemeAware implementation
    @Override
    public boolean supportsScheme(String scheme) {
        return "file".equalsIgnoreCase(scheme);
    }

    @Override
    public InputStream openUri(java.net.URI uri, SeedPackDescriptor context) throws IOException {
        if (!supportsScheme(uri.getScheme())) {
            throw new IOException("Unsupported scheme for FileSeedSource: " + uri.getScheme());
        }
        java.nio.file.Path path = java.nio.file.Path.of(uri);
        return java.nio.file.Files.newInputStream(path);
    }

    private static final class SeedSourceIOException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SeedSourceIOException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

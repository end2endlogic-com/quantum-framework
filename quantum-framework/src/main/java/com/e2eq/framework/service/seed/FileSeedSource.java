package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads seed packs from a directory structure on the filesystem.
 * Now a CDI bean that auto-discovers via SeedPathResolver.
 */
@ApplicationScoped
public class FileSeedSource implements SeedSource, SchemeAware {

    @Inject
    SeedPathResolver seedPathResolver;

    private String id = "files";
    private Path root;
    private String manifestFileName = "manifest.yaml";

    /**
     * Default constructor for CDI.
     * Root path will be resolved from SeedPathResolver on first use.
     */
    public FileSeedSource() {
        // CDI will inject SeedPathResolver
    }

    /**
     * Constructor for manual instantiation (tests, backward compatibility).
     *
     * @param id the source identifier
     * @param root the root path
     */
    public FileSeedSource(String id, Path root) {
        this(id, root, "manifest.yaml");
    }

    /**
     * Constructor for manual instantiation (tests, backward compatibility).
     *
     * @param id the source identifier
     * @param root the root path
     * @param manifestFileName the manifest file name
     */
    public FileSeedSource(String id, Path root, String manifestFileName) {
        this.id = Objects.requireNonNull(id, "id");
        this.root = Objects.requireNonNull(root, "root");
        this.manifestFileName = Objects.requireNonNull(manifestFileName, "manifestFileName");
    }

    private Path getRoot() {
        if (root == null && seedPathResolver != null) {
            root = seedPathResolver.resolveSeedRoot();
            Log.debugf("FileSeedSource: resolved root path: %s", root);
        }
        if (root == null) {
            throw new IllegalStateException("FileSeedSource root path not set and SeedPathResolver not available");
        }
        return root;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public List<SeedPackDescriptor> loadSeedPacks(SeedContext context) throws IOException {
        Path rootPath = getRoot();
        if (!Files.exists(rootPath)) {
            return List.of();
        }
        List<SeedPackDescriptor> descriptors = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(rootPath)) {
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
        Path rootPath = getRoot();
        Path datasetPath = descriptor.getManifestPath().getParent().resolve(relativePath).normalize();
        if (!datasetPath.startsWith(rootPath)) {
            throw new IOException("Dataset path " + datasetPath + " is outside seed source root " + rootPath);
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

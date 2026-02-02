package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads seed packs from the application classpath under the directory
 * "seed-packs". This allows framework-provided seeds to be discovered by
 * applications without copying files into the app project.
 * 
 * Now a CDI bean that is auto-discovered.
 */
@ApplicationScoped
public class ClasspathSeedSource implements SeedSource, SchemeAware {

    private static final String SEED_ROOT = "seed-packs";
    private final ClassLoader cl;
    private String id = "classpath";

    /**
     * Default constructor for CDI.
     */
    public ClasspathSeedSource() {
        this.cl = Thread.currentThread().getContextClassLoader();
    }

    /**
     * Constructor for manual instantiation (tests, backward compatibility).
     *
     * @param cl the class loader to use
     * @param id the source identifier
     */
    public ClasspathSeedSource(ClassLoader cl, String id) {
        this.cl = Objects.requireNonNull(cl, "classLoader");
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String getId() { return id; }

    @Override
    public List<SeedPackDescriptor> loadSeedPacks(SeedContext context) throws IOException {
        List<SeedPackDescriptor> result = new ArrayList<>();
        Enumeration<URL> roots = cl.getResources(SEED_ROOT);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            try {
                if ("file".equalsIgnoreCase(url.getProtocol())) {
                    // Running from exploded classes; walk the filesystem
                    Path rootPath = Path.of(url.toURI());
                    if (Files.exists(rootPath)) {
                        Files.walk(rootPath)
                                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals("manifest.yaml"))
                                .forEach(p -> {
                                    String description = p.toAbsolutePath().toString();
                                    try (InputStream in = Files.newInputStream(p)) {
                                        SeedPackManifest manifest = SeedPackManifest.load(in, description);
                                        // Store resource-like path so openDataset can resolve relative files on classpath
                                        Path pseudo = Path.of("/" + SEED_ROOT).resolve(rootPath.relativize(p));
                                        // Compute the classpath resource name for this manifest
                                        String rel = rootPath.relativize(p).toString().replace(File.separatorChar, '/');
                                        String resourceName = SEED_ROOT + "/" + rel;
                                        URL manifestUrl = cl.getResource(resourceName);
                                        result.add(new SeedPackDescriptor(this, manifest, pseudo, manifestUrl));
                                    } catch (IOException e) {
                                        throw new RuntimeException("Failed reading classpath seed manifest: " + description, e);
                                    }
                                });
                    }
                } else if ("jar".equalsIgnoreCase(url.getProtocol())) {
                    // Packaged in a jar
                    URLConnection conn = url.openConnection();
                    if (conn instanceof JarURLConnection) {
                        JarURLConnection juc = (JarURLConnection) conn;
                        try (JarFile jar = juc.getJarFile()) {
                            Enumeration<JarEntry> entries = jar.entries();
                            while (entries.hasMoreElements()) {
                                JarEntry entry = entries.nextElement();
                                String name = entry.getName();
                                if (!entry.isDirectory() && name.startsWith(SEED_ROOT + "/") && name.endsWith("/manifest.yaml")) {
                                    try (InputStream in = cl.getResourceAsStream(name)) {
                                        if (in == null) continue;
                                        SeedPackManifest manifest = SeedPackManifest.load(in, name);
                                        Path pseudo = Path.of("/" + name);
                                        URL manifestUrl = cl.getResource(name);
                                        result.add(new SeedPackDescriptor(this, manifest, pseudo, manifestUrl));
                                    }
                                }
                            }
                        }
                    } else {
                        Log.debugf("ClasspathSeedSource: Unsupported JAR URL connection type: %s", conn.getClass());
                    }
                } else {
                    Log.debugf("ClasspathSeedSource: Ignoring URL with unsupported protocol %s -> %s", url.getProtocol(), url);
                }
            } catch (Exception e) {
                throw new IOException("Failed to scan classpath seeds from " + url, e);
            }
        }
        return result;
    }

    @Override
    public InputStream openDataset(SeedPackDescriptor descriptor, String relativePath) throws IOException {
        // Prefer loading relative to the exact manifest URL to avoid heuristics
        URL manifestUrl = descriptor.getManifestUrl();
        String seedPack = descriptor.getManifest().getSeedPack();
        if (manifestUrl != null) {
            String protocol = manifestUrl.getProtocol();
            if ("jar".equalsIgnoreCase(protocol)) {
                // Open the same JAR and resolve dataset entries relative to the manifest entry
                URLConnection conn = manifestUrl.openConnection();
                if (conn instanceof JarURLConnection juc) {
                    try (JarFile jar = juc.getJarFile()) {
                        String entryName = juc.getEntryName(); // e.g., seed-packs/<pack>/.../manifest.yaml
                        String baseEntry = entryName.contains("/") ? entryName.substring(0, entryName.lastIndexOf('/')) : "";
                        String candidate = baseEntry.isEmpty() ? relativePath : baseEntry + "/" + relativePath;
                        JarEntry je = jar.getJarEntry(candidate);
                        if (je != null) {
                            // Read bytes within try block since JarFile will be closed after
                            try (InputStream jarIn = jar.getInputStream(je)) {
                                byte[] data = jarIn.readAllBytes();
                                return new ByteArrayInputStream(data);
                            }
                        }
                        // If not found, search within the same pack root
                        String packRoot = null;
                        String anchor = SEED_ROOT + "/" + (seedPack != null ? seedPack : "");
                        int idx = entryName.indexOf(anchor);
                        if (idx >= 0) {
                            int end = entryName.indexOf('/', idx + anchor.length());
                            packRoot = end > 0 ? entryName.substring(0, end) : anchor;
                        }
                        if (packRoot == null || packRoot.isBlank()) {
                            packRoot = baseEntry.contains("/") ? baseEntry.substring(0, baseEntry.indexOf('/')) : SEED_ROOT;
                        }
                        JarEntry best = null;
                        int bestExtra = Integer.MAX_VALUE;
                        String suffix = "/" + relativePath;
                        Enumeration<JarEntry> it = jar.entries();
                        while (it.hasMoreElements()) {
                            JarEntry e = it.nextElement();
                            String n = e.getName();
                            if (e.isDirectory()) continue;
                            if (!n.startsWith(SEED_ROOT + "/")) continue;
                            if (n.endsWith(suffix)) {
                                // Prefer the closest to baseEntry
                                int extra = Math.max(0, n.length() - suffix.length() - baseEntry.length());
                                if (extra < bestExtra) {
                                    best = e;
                                    bestExtra = extra;
                                }
                            }
                        }
                        if (best != null) {
                            // Read bytes within try block since JarFile will be closed after
                            try (InputStream jarIn = jar.getInputStream(best)) {
                                byte[] data = jarIn.readAllBytes();
                                return new ByteArrayInputStream(data);
                            }
                        }
                        Log.warnf("ClasspathSeedSource: dataset not found in JAR for pack=%s manifest=%s relative=%s", seedPack, entryName, relativePath);
                        throw new IOException("Classpath dataset not found in jar: " + relativePath);
                    }
                }
            } else if ("file".equalsIgnoreCase(protocol)) {
                // Resolve on filesystem relative to manifest file
                try {
                    URI uri = manifestUrl.toURI();
                    Path manifestPath = Paths.get(uri);
                    Path baseDir = manifestPath.getParent();
                    Path direct = baseDir.resolve(relativePath).normalize();
                    if (Files.exists(direct)) {
                        return Files.newInputStream(direct);
                    }
                    // As a fallback, search within the pack root folder if available
                    Path packRoot = findPackRoot(baseDir, seedPack);
                    if (packRoot != null && Files.exists(packRoot)) {
                        try (var stream = Files.walk(packRoot)) {
                            Path found = stream.filter(p -> Files.isRegularFile(p) && p.toString().replace(File.separatorChar, '/').endsWith("/" + relativePath))
                                    .sorted((a, b) -> {
                                        // prefer the one closest to baseDir
                                        int da = Math.abs(a.getParent().toString().length() - baseDir.toString().length());
                                        int db = Math.abs(b.getParent().toString().length() - baseDir.toString().length());
                                        return Integer.compare(da, db);
                                    })
                                    .findFirst()
                                    .orElse(null);
                            if (found != null) {
                                return Files.newInputStream(found);
                            }
                        }
                    }
                    Log.warnf("ClasspathSeedSource: dataset not found on filesystem for pack=%s manifest=%s relative=%s", seedPack, manifestPath, relativePath);
                } catch (Exception e) {
                    throw new IOException("Failed resolving dataset relative to manifest URL: " + manifestUrl, e);
                }
            }
        }

        // Fallback to previous classpath-relative logic using pseudo path
        String base = descriptor.getManifestPath().getParent().toString();
        if (base.startsWith("/")) base = base.substring(1);
        String resource = base + "/" + relativePath;
        InputStream in = cl.getResourceAsStream(resource);
        if (in != null) return in;
        throw new IOException("Classpath dataset not found: " + resource);
    }

    private static Path findPackRoot(Path baseDir, String seedPack) {
        // Try to locate the seed-packs/<pack> ancestor
        Path p = baseDir;
        while (p != null) {
            Path name = p.getFileName();
            if (name != null && name.toString().equals(seedPack)) {
                Path parent = p.getParent();
                if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals(SEED_ROOT)) {
                    return p; // seed-packs/<pack>
                }
            }
            p = p.getParent();
        }
        return null;
    }

    // SchemeAware implementation; allow jar scheme for explicit URIs (rare)
    @Override
    public boolean supportsScheme(String scheme) {
        return "jar".equalsIgnoreCase(scheme) || "classpath".equalsIgnoreCase(scheme);
    }

    @Override
    public InputStream openUri(java.net.URI uri, SeedPackDescriptor context) throws IOException {
        if (!supportsScheme(uri.getScheme())) {
            throw new IOException("Unsupported scheme for ClasspathSeedSource: " + uri.getScheme());
        }
        URL url = uri.toURL();
        return url.openStream();
    }
}

package com.e2eq.framework.service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * Routes dataset opens based on URI scheme by inspecting available SeedSource instances.
 * If the value is a relative path, delegates to the owning SeedSource.
 */
final class SeedResourceRouter {

    private final List<SeedSource> sources;

    SeedResourceRouter(List<SeedSource> sources) {
        this.sources = sources;
    }

    InputStream open(SeedPackDescriptor descriptor, String pathOrUri) throws IOException {
        if (isUri(pathOrUri)) {
            URI uri = URI.create(pathOrUri);
            String scheme = uri.getScheme();
            for (SeedSource s : sources) {
                if (s instanceof SchemeAware sa && sa.supportsScheme(scheme)) {
                    return sa.openUri(uri, descriptor);
                }
            }
            throw new IOException("No SeedSource found for URI scheme '" + scheme + "'");
        }
        // Backward compatible relative path resolution via owning source
        return descriptor.getSource().openDataset(descriptor, pathOrUri);
    }

    private static boolean isUri(String value) {
        if (value == null) return false;
        int i = value.indexOf(":");
        return i > 1 && value.contains("://");
    }
}

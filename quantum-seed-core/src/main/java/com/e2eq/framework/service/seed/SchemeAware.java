package com.e2eq.framework.service.seed;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Optional capability for SeedSource implementations to handle absolute URI-based datasets.
 * This allows manifests to reference datasets using scheme-specific URLs like file:// or s3://.
 */
public interface SchemeAware {
    /** Returns true if this source can handle the given URI scheme (e.g., "file", "s3"). */
    boolean supportsScheme(String scheme);

    /** Open a dataset by absolute URI. */
    InputStream openUri(URI uri, SeedPackDescriptor context) throws IOException;

    /**
     * Optionally load a manifest by absolute URI (e.g., for future includes/redirects by URI).
     * Default implementation indicates unsupported.
     */
    default SeedPackDescriptor loadManifestByUri(URI uri, SeedContext ctx) throws IOException {
        throw new IOException("loadManifestByUri not supported by this source");
    }
}

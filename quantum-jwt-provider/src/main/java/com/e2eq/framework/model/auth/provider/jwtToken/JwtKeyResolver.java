package com.e2eq.framework.model.auth.provider.jwtToken;

import java.io.IOException;
import java.io.InputStream;

/**
 * Resolves JWT key material from a configured location.
 * Implementations can support classpath, filesystem, vault, or other sources.
 */
public interface JwtKeyResolver {

    /**
     * Opens a stream for the configured key location.
     *
     * @param location configured key location
     * @return stream containing the PEM key material
     * @throws IOException if the location cannot be resolved
     */
    InputStream openKeyStream(String location) throws IOException;
}

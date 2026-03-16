package com.e2eq.framework.model.auth.provider.jwtToken;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Default resolver that supports filesystem and classpath key locations.
 */
@ApplicationScoped
public class DefaultJwtKeyResolver implements JwtKeyResolver {

    @Override
    public InputStream openKeyStream(String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IOException("JWT key location is not configured");
        }

        if (location.startsWith("file:")) {
            String filePath = location.substring("file:".length());
            return new FileInputStream(filePath);
        }

        String resourceName = location.startsWith("classpath:")
                ? location.substring("classpath:".length())
                : location;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream stream = loader.getResourceAsStream(resourceName);
        if (stream == null) {
            throw new IOException("Could not find key resource: " + location);
        }
        return stream;
    }
}

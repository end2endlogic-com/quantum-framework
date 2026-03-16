package com.e2eq.framework.model.auth.provider.jwtToken;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenUtilsResolverTest {

    @AfterEach
    void resetDefaults() {
        TokenUtils.configureKeyResolver(new DefaultJwtKeyResolver());
        TokenUtils.configure("privateKey.pem", "publicKey.pem");
    }

    @Test
    void loadsPrivateKeyThroughConfiguredResolver() throws Exception {
        TokenUtils.configureKeyResolver(location -> openClasspathKey());
        TokenUtils.configure("vault:tenant-a/private", "vault:tenant-a/public");

        PrivateKey first = TokenUtils.readPrivateKey("vault:tenant-a/private");
        PrivateKey second = TokenUtils.readPrivateKey("vault:tenant-a/private");

        assertSame(first, second);
    }

    @Test
    void replacingResolverInvalidatesCachedPrivateKey() throws Exception {
        TokenUtils.configureKeyResolver(location -> openClasspathKey());
        TokenUtils.configure("vault:tenant-a/private", "vault:tenant-a/public");
        PrivateKey first = TokenUtils.readPrivateKey("vault:tenant-a/private");

        TokenUtils.configureKeyResolver(location -> {
            throw new IOException("resolver replaced");
        });

        assertThrows(IOException.class, () -> TokenUtils.readPrivateKey("vault:tenant-a/private"));
        TokenUtils.configureKeyResolver(location -> openClasspathKey());
        PrivateKey reloaded = TokenUtils.readPrivateKey("vault:tenant-a/private");
        assertSame(reloaded, TokenUtils.readPrivateKey("vault:tenant-a/private"));
        org.junit.jupiter.api.Assertions.assertNotSame(first, reloaded);
    }

    private static InputStream openClasspathKey() throws IOException {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("privateKey.pem");
        if (stream == null) {
            throw new IOException("Could not find test key resource");
        }
        return stream;
    }
}

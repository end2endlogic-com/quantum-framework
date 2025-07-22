package com.e2eq.framework.util;

import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class TestTokenUtilsCache {

    @Test
    public void testPrivateKeyCaching() throws Exception {
        java.lang.reflect.Field field = TokenUtils.class.getDeclaredField("cachedPrivateKey");
        field.setAccessible(true);
        field.set(null, null);

        PrivateKey first = TokenUtils.readPrivateKey("privateKey.pem");
        assertNotNull(first);
        PrivateKey second = TokenUtils.readPrivateKey("privateKey.pem");
        assertSame(first, second);
    }

    @Test
    public void testRepeatedTokenGenerationUsesCachedKey() throws Exception {
        java.lang.reflect.Field field = TokenUtils.class.getDeclaredField("cachedPrivateKey");
        field.setAccessible(true);
        field.set(null, null);

        String token1 = TokenUtils.generateUserToken("test", Set.of("user"), TokenUtils.expiresAt(300), "issuer");
        assertNotNull(token1);
        PrivateKey loaded = (PrivateKey) field.get(null);
        assertNotNull(loaded);

        String token2 = TokenUtils.generateUserToken("test", Set.of("user"), TokenUtils.expiresAt(300), "issuer");
        assertNotNull(token2);
        PrivateKey loaded2 = (PrivateKey) field.get(null);
        assertSame(loaded, loaded2);
    }
}

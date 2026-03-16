package com.e2eq.framework.tests.util;

import com.e2eq.framework.model.auth.provider.jwtToken.TokenUtils;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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

    @Test
    public void testReadAllBytesConsumesEntireStream() throws Exception {
        Method readAllBytes = TokenUtils.class.getDeclaredMethod("readAllBytes", InputStream.class, String.class);
        readAllBytes.setAccessible(true);

        byte[] expected = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(300).getBytes(StandardCharsets.UTF_8);
        InputStream partialReadStream = new InputStream() {
            private int position;

            @Override
            public int read() {
                if (position >= expected.length) {
                    return -1;
                }
                return expected[position++] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                if (position >= expected.length) {
                    return -1;
                }
                int chunkSize = Math.min(Math.min(len, 17), expected.length - position);
                System.arraycopy(expected, position, buffer, off, chunkSize);
                position += chunkSize;
                return chunkSize;
            }
        };

        byte[] actual = (byte[]) readAllBytes.invoke(null, partialReadStream, "test-location");
        assertArrayEquals(expected, actual);
    }
}

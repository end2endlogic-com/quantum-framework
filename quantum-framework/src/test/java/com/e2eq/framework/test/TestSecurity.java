package com.e2eq.framework.test;

import com.e2eq.framework.util.EncryptionUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestSecurity {
    @Test
    void testBcrypt() {
        // should fail as should not hash above 72 characters
        try {
            EncryptionUtils.hashPassword("1234567890123456789012345678901234567890123456789012345678901234567890123456789012345");
            Assertions.fail("Expected IllegalArgumentException to be thrown");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}

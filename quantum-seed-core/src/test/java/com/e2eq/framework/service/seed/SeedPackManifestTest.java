package com.e2eq.framework.service.seed;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SeedPackManifestTest {

    @Test
    void validatorIsReusedAcrossLoads() throws Exception {
        Field validatorField = SeedPackManifest.class.getDeclaredField("VALIDATOR");
        validatorField.setAccessible(true);
        Object initialValidator = validatorField.get(null);
        assertNotNull(initialValidator);

        String yaml = """
                seedPack: demo-pack
                version: 1.0.0
                datasets:
                  - collection: demo
                    file: datasets/demo.ndjson
                    naturalKey: [refName]
                """;

        SeedPackManifest.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "mem-1");
        SeedPackManifest.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "mem-2");

        Object validatorAfterLoads = validatorField.get(null);
        assertSame(initialValidator, validatorAfterLoads);
    }
}

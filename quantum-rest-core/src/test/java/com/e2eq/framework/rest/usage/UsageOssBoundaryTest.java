package com.e2eq.framework.rest.usage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageOssBoundaryTest {

    @Test
    void usageRuntimeHasNoCommercialOrCrossTierDependency() throws IOException {
        Path root = findReactorRoot(Path.of(System.getProperty("user.dir")));

        assertOssModule(root.resolve("quantum-contract-core"));
        assertOssModule(root.resolve("quantum-rest-core"));
    }

    private static void assertOssModule(Path module) throws IOException {
        assertTrue(Files.isDirectory(module), module.getFileName() + " module must be locatable");

        String mainSources;
        try (var files = Files.walk(module.resolve("src/main"))) {
            StringBuilder joined = new StringBuilder();
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    joined.append(Files.readString(path)).append('\n');
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
            mainSources = joined.toString().toLowerCase(java.util.Locale.ROOT);
        }

        assertFalse(mainSources.contains("com.helixor"));
        assertFalse(mainSources.contains("quantum-enterprise"));
        assertFalse(mainSources.contains("@joint/plus"));
    }

    private static Path findReactorRoot(Path start) throws IOException {
        Path candidate = start.toAbsolutePath();
        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                String text = Files.readString(pom);
                if (text.contains("<artifactId>quantum-parent</artifactId>")
                        && text.contains("<module>quantum-contract-core</module>")) {
                    return candidate;
                }
            }
            candidate = candidate.getParent();
        }
        throw new IOException("Unable to locate the quantum-parent reactor from " + start);
    }
}

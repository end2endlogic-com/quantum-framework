package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.security.ApplicationDeclarationValidator.VocabularyEnforcement;
import com.e2eq.framework.model.security.FunctionalArea;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every seed pack this module ships or tests with is admitted by the identity-pack declaration gate
 * under the default WARN setting, so wiring the gate into the apply path refuses nothing that loads
 * today.
 */
class ExistingSeedPacksDeclarationGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private static final ApplicationDeclarationGate.HeldVocabulary NOTHING_HELD = new ApplicationDeclarationGate.HeldVocabulary() {
        @Override
        public List<FunctionalArea> areas(String realm) {
            return List.of();
        }

        @Override
        public List<FunctionalDomain> domains(String realm) {
            return List.of();
        }
    };

    @Test
    void everyPackIsAdmittedUnderWarn() throws IOException {
        ApplicationDeclarationGate gate = new ApplicationDeclarationGate(VocabularyEnforcement.WARN, NOTHING_HELD, JSON);
        SeedContext context = SeedContext.builder("existing-packs").build();
        List<SeedPackDescriptor> packs = new ArrayList<>();
        for (String root : List.of("src/main/resources/seed-packs", "src/test/resources/seed-packs",
                "src/test/resources/seed-fixtures", "src/test/resources/seeds")) {
            packs.addAll(new FileSeedSource(root, Path.of(root)).loadSeedPacks(context));
        }
        assertFalse(packs.isEmpty(), "no seed packs discovered");
        for (SeedPackDescriptor pack : packs) {
            assertDoesNotThrow(() -> gate.admit(pack, context, dataset -> read(pack, dataset)), pack.identity());
        }
    }

    private static List<Map<String, Object>> read(SeedPackDescriptor pack, SeedPackManifest.Dataset dataset) {
        String file = dataset.getFile();
        try (InputStream in = file.startsWith("classpath:")
                ? Thread.currentThread().getContextClassLoader().getResourceAsStream(file.substring("classpath:".length()))
                : pack.getSource().openDataset(pack, file)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    rows.add(JSON.readValue(line, MAP));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException(pack.identity() + ": " + file, e);
        }
    }
}

package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Code;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.VocabularyEnforcement;
import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalArea;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The identity-pack declaration check on the seed apply path: WARN loads and logs, REFUSE writes nothing. */
class ApplicationDeclarationGateTest {

    private static final String DOMAINS = """
            {"refName":"PLAN","applicationId":"acme-planner","area":"PLANNING","functionalActions":[{"refName":"VIEW"},{"refName":"APPROVE"}]}
            """;

    @TempDir
    Path root;

    private final List<String> written = new ArrayList<>();
    private final List<String> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(String.valueOf(record.getMessage()) + " " + java.util.Arrays.toString(record.getParameters()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLogs() {
        Logger.getLogger("").addHandler(capture);
    }

    @AfterEach
    void releaseLogs() {
        Logger.getLogger("").removeHandler(capture);
    }

    /** What another application already holds in the realm. */
    private static ApplicationDeclarationGate.HeldVocabulary held() {
        FunctionalArea system = new FunctionalArea();
        system.setRefName("SYSTEM");
        system.setApplicationId("quantum-system");
        FunctionalDomain menu = new FunctionalDomain();
        menu.setRefName("MENU_ITEM");
        menu.setArea("SYSTEM");
        menu.setApplicationId("quantum-system");
        FunctionalAction view = new FunctionalAction();
        view.setRefName("VIEW");
        menu.setFunctionalActions(new ArrayList<>(List.of(view)));
        return new ApplicationDeclarationGate.HeldVocabulary() {
            @Override
            public List<FunctionalArea> areas(String realm) {
                return List.of(system);
            }

            @Override
            public List<FunctionalDomain> domains(String realm) {
                return List.of(menu);
            }
        };
    }

    static String policy(String role, String area, String domain, String action) {
        return "{\"refName\":\"" + role.toUpperCase() + "_" + action + "\",\"applicationId\":\"acme-planner\",\"principalId\":\"" + role
                + "\",\"principalType\":\"ROLE\",\"dataDomain\":{\"tenantId\":\"t1\"},\"rules\":[{\"name\":\"r-" + action
                + "\",\"securityURI\":{\"header\":{\"identity\":\"" + role + "\",\"area\":\"" + area + "\",\"functionalDomain\":\""
                + domain + "\",\"action\":\"" + action + "\"},\"body\":{\"realm\":\"*\",\"tenantId\":\"*\"}},\"effect\":\"ALLOW\"}]}\n";
    }

    private void pack(String name, String application, String domains, String policies) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name).resolve("datasets"));
        Files.writeString(dir.getParent().resolve("manifest.yaml"), """
                seedPack: %s
                version: 1.0.0
                datasets:
                  - collection: application
                    naturalKey: [refName]
                    upsert: true
                    file: datasets/applications.jsonl
                  - collection: functionalDomain
                    naturalKey: [applicationId, area, refName]
                    upsert: true
                    file: datasets/functionalDomains.jsonl
                  - collection: policy
                    naturalKey: [refName]
                    upsert: true
                    file: datasets/policies.jsonl
                """.formatted(name));
        Files.writeString(dir.resolve("applications.jsonl"), application);
        Files.writeString(dir.resolve("functionalDomains.jsonl"), domains);
        Files.writeString(dir.resolve("policies.jsonl"), policies);
    }

    private static final String APPLICATION = """
            {"refName":"acme-planner","roles":[{"name":"planner","grantableBy":["tenant-admin"]}],"uses":[{"application":"quantum-system","area":"SYSTEM","domains":["MENU_ITEM"]}]}
            """;

    private static final String VALID_POLICIES = policy("planner", "PLANNING", "PLAN", "APPROVE")
            + policy("planner", "SYSTEM", "MENU_ITEM", "VIEW");

    private SeedLoader loader(VocabularyEnforcement mode) {
        SeedRepository repository = new SeedRepository() {
            @Override
            public void ensureIndexes(SeedContext context, SeedPackManifest.Dataset dataset, SeedPackManifest.Index index) {
            }

            @Override
            public void upsertRecord(SeedContext context, SeedPackManifest.Dataset dataset, Map<String, Object> record) {
                written.add(dataset.getCollection() + ":" + record.get("refName"));
            }
        };
        return SeedLoader.builder()
                .seedRepository(repository)
                .conflictPolicy(SeedConflictPolicy.SEED_WINS)
                .addSeedSource(new FileSeedSource("test", root))
                .addPackGate(new ApplicationDeclarationGate(mode, held(), new ObjectMapper()))
                .build();
    }

    private void apply(VocabularyEnforcement mode, String... packs) {
        List<SeedPackRef> refs = java.util.Arrays.stream(packs).map(p -> SeedPackRef.exact(p, "1.0.0")).toList();
        loader(mode).apply(refs, SeedContext.builder("test-realm").build());
    }

    @Test
    void validPackLoadsInBothModes() throws IOException {
        pack("acme-planner-identity", APPLICATION, DOMAINS, VALID_POLICIES);
        for (VocabularyEnforcement mode : VocabularyEnforcement.values()) {
            written.clear();
            apply(mode, "acme-planner-identity");
            assertTrue(written.contains("application:acme-planner"), mode + " " + written);
            assertTrue(written.contains("policy:PLANNER_APPROVE"), mode + " " + written);
        }
        assertTrue(logged.stream().noneMatch(l -> l.contains("identity-pack.declaration.warning")), logged.toString());
    }

    @Test
    void warnLogsAPolicyNamingAnUnknownActionAndLoads() throws IOException {
        pack("acme-planner-identity", APPLICATION, DOMAINS, VALID_POLICIES + policy("planner", "PLANNING", "PLAN", "PUBLISH"));
        apply(VocabularyEnforcement.WARN, "acme-planner-identity");
        assertTrue(written.contains("policy:PLANNER_PUBLISH"), written.toString());
        assertTrue(logged.stream().anyMatch(l -> l.contains("identity-pack.declaration.warning")
                && l.contains(Code.POLICY_TRIPLE_UNDECLARED.name()) && l.contains("publish")), logged.toString());
    }

    @Test
    void refuseRejectsAnUnknownActionAndWritesNothing() throws IOException {
        // A clean pack applied first in the same request: all or nothing covers it too.
        pack("acme-base", "{\"refName\":\"acme-base\"}\n", "", "");
        pack("acme-planner-identity", APPLICATION, DOMAINS, VALID_POLICIES + policy("planner", "PLANNING", "PLAN", "PUBLISH"));
        ApplicationDeclarationRefusedException refused = assertThrows(ApplicationDeclarationRefusedException.class,
                () -> apply(VocabularyEnforcement.REFUSE, "acme-base", "acme-planner-identity"));
        assertEquals("acme-planner-identity@1.0.0", refused.getSeedPack());
        assertEquals("acme-planner", refused.getApplicationId());
        assertEquals(Code.POLICY_TRIPLE_UNDECLARED, refused.getDiagnostics().get(0).code());
        assertTrue(refused.getMessage().contains(ApplicationDeclarationRefusedException.CODE), refused.getMessage());
        assertTrue(refused.getMessage().contains("publish"), refused.getMessage());
        assertEquals(List.of(), written);
    }

    @Test
    void refuseRejectsAPolicyNamingAnUnknownDomain() throws IOException {
        pack("acme-planner-identity", APPLICATION, DOMAINS, VALID_POLICIES + policy("planner", "PLANNING", "BUDGET", "VIEW"));
        ApplicationDeclarationRefusedException refused = assertThrows(ApplicationDeclarationRefusedException.class,
                () -> apply(VocabularyEnforcement.REFUSE, "acme-planner-identity"));
        assertTrue(refused.getMessage().contains("planning/budget"), refused.getMessage());
        assertEquals(List.of(), written);
    }

    @Test
    void refuseRejectsAnAreaAnotherApplicationOwns() throws IOException {
        String domains = DOMAINS + """
                {"refName":"SETTINGS","applicationId":"acme-planner","area":"SYSTEM","functionalActions":[{"refName":"VIEW"}]}
                """;
        pack("acme-planner-identity", APPLICATION.replace(",\"uses\":[{\"application\":\"quantum-system\",\"area\":\"SYSTEM\",\"domains\":[\"MENU_ITEM\"]}]", ""),
                domains, policy("planner", "PLANNING", "PLAN", "APPROVE"));
        apply(VocabularyEnforcement.WARN, "acme-planner-identity");
        assertTrue(logged.stream().anyMatch(l -> l.contains(Code.AREA_OWNED_ELSEWHERE.name())), logged.toString());
        written.clear();
        ApplicationDeclarationRefusedException refused = assertThrows(ApplicationDeclarationRefusedException.class,
                () -> apply(VocabularyEnforcement.REFUSE, "acme-planner-identity"));
        assertTrue(refused.getDiagnostics().stream().anyMatch(d -> d.code() == Code.AREA_OWNED_ELSEWHERE), refused.getMessage());
        assertEquals(List.of(), written);
    }

    @Test
    void declarationErrorsRefuseEvenUnderWarn() throws IOException {
        pack("acme-planner-identity", "{\"refName\":\"acme-planner\",\"roles\":[{\"name\":\"admin\"}]}\n", DOMAINS,
                policy("admin", "PLANNING", "PLAN", "VIEW"));
        ApplicationDeclarationRefusedException refused = assertThrows(ApplicationDeclarationRefusedException.class,
                () -> apply(VocabularyEnforcement.WARN, "acme-planner-identity"));
        assertEquals(Code.ROLE_RESERVED, refused.getDiagnostics().get(0).code());
        assertEquals(List.of(), written);
    }

    @Test
    void packsWithoutAnApplicationDatasetPassUntouched() throws IOException {
        Path dir = Files.createDirectories(root.resolve("codes").resolve("datasets"));
        Files.writeString(dir.getParent().resolve("manifest.yaml"), """
                seedPack: codes
                version: 1.0.0
                datasets:
                  - collection: codeList
                    naturalKey: [refName]
                    upsert: true
                    file: datasets/codes.jsonl
                """);
        Files.writeString(dir.resolve("codes.jsonl"), "{\"refName\":\"A\"}\n");
        apply(VocabularyEnforcement.REFUSE, "codes");
        assertEquals(List.of("codeList:A"), written);
    }

    @Test
    void severalApplicationRowsWarnUnderWarnAndRefuseUnderRefuse() throws IOException {
        pack("catalog", "{\"refName\":\"a\"}\n{\"refName\":\"b\"}\n", "", "");
        apply(VocabularyEnforcement.WARN, "catalog");
        assertTrue(written.contains("application:a"), written.toString());
        written.clear();
        ApplicationDeclarationRefusedException refused = assertThrows(ApplicationDeclarationRefusedException.class,
                () -> apply(VocabularyEnforcement.REFUSE, "catalog"));
        assertNull(refused.getApplicationId());
        assertEquals(Code.APPLICATION_DECLARATION_AMBIGUOUS, refused.getDiagnostics().get(0).code());
        assertEquals(List.of(), written);
    }
}

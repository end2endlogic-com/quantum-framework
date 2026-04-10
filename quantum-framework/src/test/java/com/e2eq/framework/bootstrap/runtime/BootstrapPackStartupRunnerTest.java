package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.ApplyBootstrapPackRequest;
import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BootstrapPackStartupRunnerTest {

    @Test
    void appliesFilteredPacksAcrossConfiguredRealms() {
        RecordingBootstrapPackService service = new RecordingBootstrapPackService();
        service.packs = List.of(
                new BootstrapPackDefinition("pack-a", "1", "b2bi", "local-demo", List.of()),
                new BootstrapPackDefinition("pack-b", "1", "b2bi", "local-demo", List.of())
        );

        BootstrapPackStartupRunner runner = new BootstrapPackStartupRunner();
        runner.bootstrapPackService = service;
        runner.envConfigUtils = env("system-b2bi-com", "b2bi-com", "test-b2bi-com");
        runner.enabled = true;
        runner.applyOnStartup = true;
        runner.startupRealmsCsv = java.util.Optional.of("b2bi-com,test-b2bi-com");
        runner.startupPackFilterCsv = java.util.Optional.of("pack-b");
        runner.quarkusProfile = "dev";

        runner.onStart();

        assertEquals(2, service.applied.size());
        assertEquals("pack-b", service.applied.get(0).packRef());
        assertEquals("b2bi-com", service.applied.get(0).realmRef());
        assertEquals("pack-b", service.applied.get(1).packRef());
        assertEquals("test-b2bi-com", service.applied.get(1).realmRef());
        assertEquals(2, service.principalRealms.size());
        assertEquals("b2bi-com", service.principalRealms.get(0));
        assertEquals("test-b2bi-com", service.principalRealms.get(1));
    }

    private static EnvConfigUtils env(String systemRealm, String defaultRealm, String testRealm) {
        EnvConfigUtils utils = new EnvConfigUtils();
        utils.setSystemRealm(systemRealm);
        utils.setSystemTenantId("system.com");
        utils.setSystemOrgRefName("system.com");
        utils.setSystemAccountNumber("0000000000");
        utils.setSystemUserId("system@system.com");
        utils.setDefaultRealm(defaultRealm);
        utils.setDefaultTenantId("b2bi.com");
        utils.setDefaultOrgRefName("b2bi.com");
        utils.setDefaultAccountNumber("0000000001");
        utils.setTestRealm(testRealm);
        utils.setTestTenantId("test.com");
        utils.setTestOrgRefName("test.com");
        utils.setTestAccountNumber("9999999999");
        return utils;
    }

    private static final class RecordingBootstrapPackService extends BootstrapPackService {
        private List<BootstrapPackDefinition> packs = List.of();
        private final List<ApplyBootstrapPackRequest> applied = new ArrayList<>();
        private final List<String> principalRealms = new ArrayList<>();

        RecordingBootstrapPackService() {
            super(null, List.of(), null);
        }

        @Override
        public List<BootstrapPackDefinition> listPacks() {
            return packs;
        }

        @Override
        public com.e2eq.framework.bootstrap.model.BootstrapPackRun apply(ApplyBootstrapPackRequest request) {
            applied.add(request);
            principalRealms.add(SecurityContext.getPrincipalContext().map(ctx -> ctx.getDefaultRealm()).orElse(null));
            return null;
        }
    }
}

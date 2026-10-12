package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.model.security.TenantProvisioningRun;
import com.e2eq.framework.service.TenantProvisioningService.ProvisionTenantCommand;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantProvisioningRequestIdentityTest {
    private ProvisionTenantCommand command() {
        return ProvisionTenantCommand.builder().tenantEmailDomain("acme.example")
            .orgRefName("acme").accountId("account-1").adminUserId("operator")
            .adminSubject("subject-1").applicationId("test-app").build();
    }

    @Test
    void everyNonSecretCommandFieldParticipatesInIdentity() throws Exception {
        String original = TenantProvisioningRunService.requestFingerprint(command());
        for (var field : ProvisionTenantCommand.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
            var changed = command();
            field.setAccessible(true);
            if (field.getType() == String.class) field.set(changed, "changed");
            else if (field.getType() == boolean.class) field.setBoolean(changed, !field.getBoolean(changed));
            else if (field.getType().isEnum()) field.set(changed, field.getType().getEnumConstants()[0]);
            else if (field.getType() == List.class) field.set(changed, List.of("changed"));
            else fail("Add a request identity test value for " + field.getName());
            String fingerprint = TenantProvisioningRunService.requestFingerprint(changed);
            if (field.getName().equals("adminPassword")) assertEquals(original, fingerprint);
            else assertNotEquals(original, fingerprint, "Command field is not pinned: " + field.getName());
        }
    }

    @Test
    void sameOperationRejectsChangedPlanAndUnpinnedLegacyRun() {
        var run = TenantProvisioningRun.builder()
            .requestFingerprint(TenantProvisioningRunService.requestFingerprint(command())).build();
        assertDoesNotThrow(() -> TenantProvisioningRunService.requireMatchingRequest(run,
            TenantProvisioningRunService.requestFingerprint(command())));
        var changed = command();
        changed.setArchetypes(List.of("different-seeds"));
        assertTrue(assertThrows(IllegalStateException.class, () ->
            TenantProvisioningRunService.requireMatchingRequest(run,
                TenantProvisioningRunService.requestFingerprint(changed)))
            .getMessage().startsWith("PROVISIONING_REQUEST_CONFLICT"));
        run.setRequestFingerprint(null);
        assertTrue(assertThrows(IllegalStateException.class, () ->
            TenantProvisioningRunService.requireMatchingRequest(run,
                TenantProvisioningRunService.requestFingerprint(command())))
            .getMessage().startsWith("PROVISIONING_REQUEST_UNPINNED"));
    }
    @Test
    void repeatedSubmissionObservesFailedRunWithoutReexecuting() {
        var run = TenantProvisioningRun.builder().executionRef("installation-request-0001")
            .requestFingerprint(TenantProvisioningRunService.requestFingerprint(command()))
            .status(TenantProvisioningRun.Status.FAILED).build();
        var service = new TenantProvisioningRunService() {
            @Override public com.e2eq.framework.rest.responses.TenantProvisioningRunResponse retry(String ref, String password) {
                throw new AssertionError("Submission must not implicitly retry a failed operation");
            }
        };
        service.envConfigUtils = new com.e2eq.framework.util.EnvConfigUtils() {
            @Override public String getSystemRealm() { return "test-authority"; }
        };
        service.runRepo = new com.e2eq.framework.model.persistent.morphia.TenantProvisioningRunRepo() {
            @Override public java.util.Optional<TenantProvisioningRun> findByExecutionRef(String realm, String ref) {
                return java.util.Optional.of(run);
            }
        };
        assertEquals("FAILED", service.startProvisioning(command(), run.getExecutionRef()).getStatus());
    }

}

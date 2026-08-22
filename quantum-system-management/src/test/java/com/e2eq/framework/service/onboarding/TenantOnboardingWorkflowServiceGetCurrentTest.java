package com.e2eq.framework.service.onboarding;

import com.e2eq.framework.model.persistent.morphia.TenantOnboardingWorkflowRepo;
import com.e2eq.framework.model.security.TenantOnboardingWorkflow;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TenantOnboardingWorkflowServiceGetCurrentTest {

    @Test
    void getCurrentDoesNotPersistWhenNoWorkflowExists() {
        AtomicInteger saves = new AtomicInteger();
        TenantOnboardingWorkflowService service = new TenantOnboardingWorkflowService();
        TenantOnboardingWorkflowDefaults defaults = new TenantOnboardingWorkflowDefaults();
        defaults.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        service.defaults = defaults;
        service.workflowRepo = new TenantOnboardingWorkflowRepo() {
            @Override
            public Optional<TenantOnboardingWorkflow> findActive(String realm) {
                return Optional.empty();
            }

            @Override
            public Optional<TenantOnboardingWorkflow> findDefault(String realm) {
                return Optional.empty();
            }

            @Override
            public TenantOnboardingWorkflow save(String realm, TenantOnboardingWorkflow value) {
                saves.incrementAndGet();
                return value;
            }
        };

        var response = service.getCurrentWorkflow("helixor-code-P1");

        assertEquals(0, saves.get());
        assertNull(response.getId());
        assertEquals(TenantOnboardingWorkflow.DEFAULT_REF_NAME, response.getRefName());
    }
}

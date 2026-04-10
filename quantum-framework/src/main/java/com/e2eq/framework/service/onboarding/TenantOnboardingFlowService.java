package com.e2eq.framework.service.onboarding;

import com.e2eq.framework.rest.requests.TenantOnboardingRunStartRequest;
import com.e2eq.framework.rest.requests.TenantOnboardingWorkflowRequest;
import com.e2eq.framework.rest.responses.TenantOnboardingRunResponse;
import com.e2eq.framework.rest.responses.TenantOnboardingWorkflowResponse;

import java.util.Optional;

/**
 * Framework seam for tenant onboarding definition and execution behavior.
 *
 * <p>The open-source framework provides a default task-backed implementation so
 * applications can model onboarding without depending on the enterprise
 * workflow runtime. Enterprise modules can override this bean with a richer
 * workflow-platform implementation.</p>
 */
public interface TenantOnboardingFlowService {

    TenantOnboardingWorkflowResponse getCurrentWorkflow(String realm);

    TenantOnboardingWorkflowResponse saveCurrentWorkflow(String realm, TenantOnboardingWorkflowRequest request);

    TenantOnboardingRunResponse startWorkflow(String realm, TenantOnboardingRunStartRequest request);

    Optional<TenantOnboardingRunResponse> getWorkflowRun(String realm, String runRef);
}

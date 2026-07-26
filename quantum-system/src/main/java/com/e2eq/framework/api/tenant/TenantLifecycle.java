package com.e2eq.framework.api.tenant;

/**
 * Control-plane contract for tenant lifecycle operations
 * (CONTROL_PLANE_SPLIT_DESIGN.md Phase B; wp1-platform-readiness.md B1).
 *
 * Embedded mode: implemented by TenantProvisioningService in quantum-framework
 * (the today's-behavior path). Remote mode (Phase C): implemented by an HTTP
 * client to the control-plane service. Consumers that should not depend on the
 * embedded provisioning machinery — admin REST resources headed for the
 * quantum-system-rest jar, system-plane orchestration — inject this
 * interface instead of the concrete service.
 *
 * Contract DTOs ({@link TenantProvisionRequest}, {@link TenantProvisionResult},
 * {@link TenantDeleteResult}) mirror the embedded service's types rather than
 * replacing them: existing TenantProvisioningService signatures are binding
 * (wp3 compatibility rule 2).
 */
public interface TenantLifecycle {

    /**
     * Provision or admit a tenant according to
     * {@link TenantProvisionRequest#deploymentTopology()}. Dedicated
     * provisioning owns realm lifecycle; pooled admission targets an existing
     * realm while DataDomain remains the tenant visibility boundary.
     */
    TenantProvisionResult provision(TenantProvisionRequest request);

    /**
     * Governed topology-aware tenant decommissioning.
     *
     * <p>The default preserves compatibility for existing lifecycle adapters
     * while failing closed. A control-plane implementation must verify the
     * archive and the topology-specific destructive boundary before returning
     * a result.</p>
     */
    default TenantDecommissionResult decommission(TenantDecommissionRequest request) {
        throw new TenantDecommissionUnavailableException(
            "No governed tenant decommission workflow is installed");
    }

    /**
     * Legacy compatibility boundary for tenant deletion.
     *
     * <p>The embedded open-source implementation must fail closed rather than
     * deleting realm data synchronously. Soft decommission, verified archive,
     * destructive approval, and restore belong to the governed system control
     * plane workflow.</p>
     */
    TenantDeleteResult delete(String realmId);
}

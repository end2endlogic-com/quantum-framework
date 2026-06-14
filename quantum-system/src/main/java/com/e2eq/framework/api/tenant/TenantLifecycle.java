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
     * Provision a tenant: realm catalog entry, realm membership, migrations,
     * admin/demo identities, base seed packs, requested archetypes,
     * verification. Idempotent per the embedded implementation's semantics
     * (re-provisioning an existing realm reports realmCreated=false and
     * warnings rather than failing).
     */
    TenantProvisionResult provision(TenantProvisionRequest request);

    /**
     * Delete a tenant's realm: catalog entry, database, credentials.
     * Implementations refuse to delete system realms.
     */
    TenantDeleteResult delete(String realmId);
}

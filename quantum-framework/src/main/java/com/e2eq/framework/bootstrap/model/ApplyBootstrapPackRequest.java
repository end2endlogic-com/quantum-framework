package com.e2eq.framework.bootstrap.model;

public record ApplyBootstrapPackRequest(
        String packRef,
        BootstrapPackApplyMode mode,
        String productRef,
        String environmentRef,
        String realmRef,
        String tenantRef,
        String workspaceRef,
        String actorRef
) {
    public ApplyBootstrapPackRequest {
        mode = mode == null ? BootstrapPackApplyMode.APPLY_MISSING : mode;
    }
}

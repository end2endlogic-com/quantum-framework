package com.e2eq.framework.bootstrap.model;

import java.util.Map;

public record ApplyBootstrapPackRequest(
        String packRef,
        BootstrapPackApplyMode mode,
        String productRef,
        String environmentRef,
        String realmRef,
        String tenantRef,
        String workspaceRef,
        String actorRef,
        Map<String, Object> scopeAttributes
) {
    public ApplyBootstrapPackRequest {
        mode = mode == null ? BootstrapPackApplyMode.APPLY_MISSING : mode;
        scopeAttributes = scopeAttributes == null ? Map.of() : Map.copyOf(scopeAttributes);
    }

    public ApplyBootstrapPackRequest(
        String packRef,
        BootstrapPackApplyMode mode,
        String productRef,
        String environmentRef,
        String realmRef,
        String tenantRef,
        String workspaceRef,
        String actorRef
    ) {
        this(packRef, mode, productRef, environmentRef, realmRef, tenantRef,
            workspaceRef, actorRef, Map.of());
    }
}

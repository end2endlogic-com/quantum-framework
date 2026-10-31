package com.e2eq.framework.api.tenant;

/**
 * Typed fail-closed signal used when no governed decommission workflow is
 * installed.
 */
public final class TenantDecommissionUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public TenantDecommissionUnavailableException(String message) {
        super(message);
    }
}

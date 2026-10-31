package com.e2eq.framework.api.tenant;

import java.util.Objects;

/**
 * Revokes and verifies tenant access before any archive or destructive step.
 *
 * <p>{@link RevocationResult#verified()} means both new authentication and
 * already-issued sessions/tokens can no longer read or write the tenant
 * scope. Suspending only the system-plane membership is insufficient for a
 * stateless tenant plane and must not be reported as verified.</p>
 */
public interface TenantAccessRevocationProvider {

    RevocationResult revokeAndVerify(RevocationRequest request);

    record RevocationRequest(
        String executionRef,
        String realmId,
        TenantDataScope tenantScope
    ) {
        public RevocationRequest {
            executionRef = required(executionRef, "executionRef");
            realmId = required(realmId, "realmId");
            tenantScope = Objects.requireNonNull(tenantScope, "tenantScope cannot be null");
        }
    }

    record RevocationResult(
        long suspendedMembershipCount,
        long suspendedUserAssignmentCount,
        boolean verified
    ) {
        public RevocationResult {
            if (suspendedMembershipCount < 0 || suspendedUserAssignmentCount < 0) {
                throw new IllegalArgumentException("revocation counts cannot be negative");
            }
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}

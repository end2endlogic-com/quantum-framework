package com.e2eq.framework.api.tenant;

import java.util.Objects;

/**
 * Canonical tenant identity used to select tenant-owned documents inside a
 * pooled realm.
 *
 * <p>{@code ownerId} is deliberately excluded. It may narrow access policy,
 * but it is not part of tenant identity and therefore cannot safely define a
 * complete tenant archive or purge.</p>
 */
public record TenantDataScope(
    String orgRefName,
    String accountNum,
    String tenantId,
    int dataSegment
) {
    public TenantDataScope {
        orgRefName = required(orgRefName, "orgRefName");
        accountNum = required(accountNum, "accountNum");
        tenantId = required(tenantId, "tenantId");
        if (dataSegment < 0) {
            throw new IllegalArgumentException("dataSegment cannot be negative");
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

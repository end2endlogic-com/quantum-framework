package com.e2eq.framework.api.tenant;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonical physical realm naming for tenant placement.
 *
 * <p>Logical tenants and organizations are not physical realms. A physical
 * realm is either a dedicated tenant realm or a shared pod realm:</p>
 *
 * <ul>
 *   <li>Dedicated: {@code {appRefName}-D-{tenantId}}</li>
 *   <li>Shared pod: {@code {appRefName}-P{podNumber}}</li>
 * </ul>
 */
public final class TenantRealmNaming {

    private TenantRealmNaming() {
    }

    public static String dedicatedRealm(String appRefName, String tenantId) {
        return normalizeSegment(appRefName, "appRefName")
            + "-D-"
            + normalizeSegment(tenantId, "tenantId");
    }

    public static String pooledRealm(String appRefName, int podNumber) {
        if (podNumber < 1) {
            throw new IllegalArgumentException("podNumber must be >= 1");
        }
        return normalizeSegment(appRefName, "appRefName") + "-P" + podNumber;
    }

    public static String normalizeSegment(String value, String fieldName) {
        String raw = Objects.requireNonNull(value, fieldName + " cannot be null").trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+", "")
            .replaceAll("-+$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must contain a letter or digit");
        }
        return normalized;
    }
}

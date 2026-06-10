package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Lightweight projection of a Realm carrying only the fields needed by
 * the {@code matching-realms} endpoint: the realm's reference name and
 * its tenant identifier.
 */
@RegisterForReflection
public record RealmInfo(String refName, String tenantId) {}

package com.e2eq.framework.bootstrap.model;

/** Exact checkpoint selected by the operator; stale recovery requests are rejected. */
public record RecoverTenantInitializationRequest(long expectedRevision, String reason) { }

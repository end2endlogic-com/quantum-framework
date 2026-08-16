package com.e2eq.framework.rest.usage;

/** Typed enforcement-state failure that REST runtimes map to a fail-closed HTTP 503. */
public final class UsageEnforcementStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String ENDPOINT_IDENTITY_UNAVAILABLE = "USAGE_ENDPOINT_IDENTITY_UNAVAILABLE";
    public static final String PRINCIPAL_IDENTITY_UNAVAILABLE = "USAGE_PRINCIPAL_IDENTITY_UNAVAILABLE";
    public static final String POLICY_SOURCE_UNAVAILABLE = "USAGE_POLICY_SOURCE_UNAVAILABLE";
    public static final String POLICY_NOT_FOUND = "USAGE_POLICY_NOT_FOUND";
    public static final String BUCKET_CAPACITY_UNAVAILABLE = "USAGE_BUCKET_CAPACITY_UNAVAILABLE";

    private final String code;

    public UsageEnforcementStateException(String code, String message) {
        super(message);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public UsageEnforcementStateException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}

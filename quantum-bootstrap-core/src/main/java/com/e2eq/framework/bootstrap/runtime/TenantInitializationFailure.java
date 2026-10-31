package com.e2eq.framework.bootstrap.runtime;

public final class TenantInitializationFailure extends RuntimeException {
    private final int status;
    private final String code;
    public TenantInitializationFailure(int status, String code, String detail) {
        super(detail); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}

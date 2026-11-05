package com.e2eq.framework.security.session;

/** Stable diagnostics for invalid explicit policy configuration or output. */
public final class SessionSelectionException extends IllegalStateException {
    public enum Code { INCOMPLETE_CONFIGURATION, MISSING_POLICY, DUPLICATE_POLICY, VERSION_MISMATCH,
        INVALID_RESULT, POLICY_FAILED }
    private final Code code;

    public SessionSelectionException(Code code, String diagnostic) {
        super("SESSION_SELECTION_" + code.name() + ": " + diagnostic);
        this.code = code;
    }

    public Code getCode() { return code; }
}

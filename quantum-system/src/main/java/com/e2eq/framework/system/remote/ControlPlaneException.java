package com.e2eq.framework.system.remote;

import java.util.OptionalInt;

/**
 * Typed failure at the tenant -> control-plane seam (remote mode). Callers can
 * branch on {@link #kind()} and surface {@link #operation()} / {@link #httpStatus()}
 * as diagnostics instead of parsing messages. There is never a local fallback:
 * a remote-mode deployment that cannot reach or is refused by the control plane
 * fails the operation.
 *
 * <p>Extends {@link IllegalStateException} so existing fail-loud callers keep
 * their semantics.</p>
 */
public class ControlPlaneException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** quantum.system-service.base-url (or equivalent) is not configured. */
        NOT_CONFIGURED,
        /** The control plane could not be reached (connect/read failure). */
        UNREACHABLE,
        /** The control plane answered with a non-2xx status. */
        REJECTED,
        /** The control plane answered 2xx but the payload violates the contract. */
        CONTRACT_VIOLATION
    }

    private final Kind kind;
    private final String operation;
    private final Integer httpStatus;

    public ControlPlaneException(Kind kind, String operation, Integer httpStatus,
                                 String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.operation = operation;
        this.httpStatus = httpStatus;
    }

    public static ControlPlaneException notConfigured(String operation, String property) {
        return new ControlPlaneException(Kind.NOT_CONFIGURED, operation, null,
            property + " is required for remote " + operation + " — failing loud, no local fallback.",
            null);
    }

    public static ControlPlaneException unreachable(String operation, Throwable cause) {
        return new ControlPlaneException(Kind.UNREACHABLE, operation, null,
            "Control plane unreachable for " + operation + " — failing loud, no local fallback.",
            cause);
    }

    public static ControlPlaneException rejected(String operation, int httpStatus, Throwable cause) {
        return new ControlPlaneException(Kind.REJECTED, operation, httpStatus,
            "Control plane returned HTTP " + httpStatus + " for " + operation, cause);
    }

    public static ControlPlaneException contractViolation(String operation, String detail) {
        return new ControlPlaneException(Kind.CONTRACT_VIOLATION, operation, null,
            "Control plane response for " + operation + " violates the contract: " + detail, null);
    }

    public Kind kind() {
        return kind;
    }

    public String operation() {
        return operation;
    }

    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }
}

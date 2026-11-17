package com.e2eq.framework.security.principal;

import java.util.Objects;

/** A typed refusal from {@link PrincipalContextResolver} in {@link PrincipalContextRequest.Mode#IDENTITY} mode. */
public class PrincipalResolutionException extends RuntimeException {

    private final PrincipalResolutionError error;

    public PrincipalResolutionException(PrincipalResolutionError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
    }

    public PrincipalResolutionError error() {
        return error;
    }

    public String code() {
        return error.name();
    }

    public int httpStatus() {
        return error.httpStatus();
    }
}

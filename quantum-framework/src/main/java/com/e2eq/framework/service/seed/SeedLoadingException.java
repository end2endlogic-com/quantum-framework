package com.e2eq.framework.service.seed;

/**
 * Generic runtime exception for seeding failures.
 */
public final class SeedLoadingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SeedLoadingException(String message) {
        super(message);
    }

    public SeedLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}

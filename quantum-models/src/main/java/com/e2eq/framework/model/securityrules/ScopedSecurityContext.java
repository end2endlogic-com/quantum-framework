package com.e2eq.framework.model.securityrules;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Try-with-resources wrapper around {@link SecurityCallScope#open(PrincipalContext, ResourceContext)}
 * for non-request threads (startup jobs, async work) that need an explicit principal and resource context.
 *
 * <p>Prefer building a {@link PrincipalContext} for the tenant/realm under work (for example from a
 * seeded credential’s {@link com.e2eq.framework.model.persistent.base.DataDomain}) rather than reusing
 * the global system principal when the operation is tenant-specific.</p>
 *
 * @see SecurityCallScope
 */
public final class ScopedSecurityContext implements AutoCloseable {

    private final SecurityCallScope.Scope delegate;

    private ScopedSecurityContext(SecurityCallScope.Scope delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Opens a scope that installs the given principal and resource context until {@link #close()}.
     */
    public static ScopedSecurityContext open(PrincipalContext principal, ResourceContext resource) {
        return new ScopedSecurityContext(SecurityCallScope.open(principal, resource));
    }

    /**
     * Runs {@code action} with {@code principal} and {@code resource} installed for the current thread.
     */
    public static void runWith(PrincipalContext principal, ResourceContext resource, Runnable action) {
        Objects.requireNonNull(action, "action");
        try (ScopedSecurityContext ignored = open(principal, resource)) {
            action.run();
        }
    }

    /**
     * Runs {@code action} with {@code principal} and {@code resource} installed; returns the supplier result.
     */
    public static <T> T runWith(PrincipalContext principal, ResourceContext resource, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        try (ScopedSecurityContext ignored = open(principal, resource)) {
            return action.get();
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}

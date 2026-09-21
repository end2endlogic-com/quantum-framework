package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.DataDomain;

/**
 * Tagged result of resolving a record, query, or graph traversal hop to a DataDomain placement.
 *
 * <p>Carries the resolution decision on the type tag ({@link Resolved} vs {@link Unresolvable}),
 * ensuring fail-closed semantics by construction without relying on look-alike sentinel objects.</p>
 */
public abstract sealed class DataDomainResolution
        permits DataDomainResolution.Resolved, DataDomainResolution.Unresolvable {

    private DataDomainResolution() { }

    /** True iff this is a {@link Resolved} placement carrying a concrete DataDomain. */
    public abstract boolean isResolved();

    /**
     * The resolved DataDomain.
     * @throws IllegalStateException if this is {@link Unresolvable} — callers MUST check
     *         {@link #isResolved()} first.
     */
    public abstract DataDomain dataDomain();

    /** The reason this operation could not be resolved, or {@code null} when {@link #isResolved()}. */
    public abstract String reason();

    public static DataDomainResolution resolved(DataDomain dataDomain) {
        if (dataDomain == null) {
            throw new IllegalArgumentException("Resolved DataDomainResolution requires a non-null DataDomain");
        }
        return new Resolved(dataDomain);
    }

    public static DataDomainResolution unresolvable(String reason) {
        return new Unresolvable(reason == null ? "unresolvable" : reason);
    }

    /** A successful placement carrying the concrete DataDomain. */
    public static final class Resolved extends DataDomainResolution {
        private final DataDomain dataDomain;

        private Resolved(DataDomain dataDomain) {
            this.dataDomain = dataDomain;
        }

        @Override public boolean isResolved() { return true; }
        @Override public DataDomain dataDomain() { return dataDomain; }
        @Override public String reason() { return null; }

        @Override public String toString() {
            return "Resolved[" + dataDomain + "]";
        }
    }

    /**
     * A failed resolution: the operation could NOT be placed into a DataDomain.
     * Fail-closed by construction.
     */
    public static final class Unresolvable extends DataDomainResolution {
        private final String reason;

        private Unresolvable(String reason) {
            this.reason = reason;
        }

        @Override public boolean isResolved() { return false; }

        @Override public DataDomain dataDomain() {
            throw new IllegalStateException(
                    "DataDomainResolution is Unresolvable (" + reason + "); no DataDomain available — "
                  + "callers must check isResolved() and fail-closed");
        }

        @Override public String reason() { return reason; }

        @Override public String toString() {
            return "Unresolvable[" + reason + "]";
        }
    }
}

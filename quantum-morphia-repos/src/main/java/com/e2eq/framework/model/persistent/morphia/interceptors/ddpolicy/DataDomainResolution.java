package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;

/**
 * Tagged result of resolving an ingest row to a DataDomain placement.
 *
 * <p>This type replaces the S1 in-band {@code UNRESOLVABLE} look-alike sentinel for the governed
 * ingest path (S3). The S1 sentinel was a real-looking {@link DataDomain} instance whose ONLY
 * distinguishing property was object identity ({@code ==}). The S1 code_review flagged that as
 * fail-OPEN-fragile: any code that clones, serializes/round-trips, or value-copies a DataDomain
 * (which Morphia, Jackson, and the repo's own clone-on-insert all do) would produce an
 * UNRESOLVABLE-VALUED domain that is NOT {@code ==} the sentinel — so the {@code ==} quarantine
 * check would silently pass it through and STAMP a "__UNRESOLVABLE__" placement as if it were a
 * real, governed domain.</p>
 *
 * <p>Here the "could not place this row" decision is carried by the TYPE TAG
 * ({@link Unresolvable}), never by the contents of a DataDomain. There is no DataDomain instance
 * at all in the unresolvable case, so there is nothing to clone, round-trip, or mis-compare: a
 * caller MUST branch on {@link #isResolved()} (or pattern-match the subtype) to obtain a
 * DataDomain, and the only way to get one is the {@link Resolved} arm. Fail-closed by construction.</p>
 */
public abstract sealed class DataDomainResolution
        permits DataDomainResolution.Resolved, DataDomainResolution.Unresolvable {

    private DataDomainResolution() { }

    /** True iff this is a {@link Resolved} placement carrying a concrete DataDomain. */
    public abstract boolean isResolved();

    /**
     * The resolved DataDomain.
     * @throws IllegalStateException if this is {@link Unresolvable} — callers MUST check
     *         {@link #isResolved()} first, which is the whole point of the tag.
     */
    public abstract DataDomain dataDomain();

    /** The reason this row could not be placed, or {@code null} when {@link #isResolved()}. */
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

    /** A successful placement carrying the concrete DataDomain to stamp. */
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
     * A failed placement: the row could NOT be governed into a DataDomain. There is deliberately
     * no DataDomain field — the quarantine decision can never be defeated by a cloned/round-tripped
     * look-alike value.
     */
    public static final class Unresolvable extends DataDomainResolution {
        private final String reason;

        private Unresolvable(String reason) {
            this.reason = reason;
        }

        @Override public boolean isResolved() { return false; }

        @Override public DataDomain dataDomain() {
            throw new IllegalStateException(
                    "DataDomainResolution is Unresolvable (" + reason + "); no DataDomain to stamp — "
                  + "callers must check isResolved() and quarantine instead");
        }

        @Override public String reason() { return reason; }

        @Override public String toString() {
            return "Unresolvable[" + reason + "]";
        }
    }
}

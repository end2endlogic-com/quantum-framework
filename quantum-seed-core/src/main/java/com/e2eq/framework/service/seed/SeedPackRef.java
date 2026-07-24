package com.e2eq.framework.service.seed;

import java.util.Objects;

import org.semver4j.Semver;
import org.semver4j.SemverException;

/**
 * Reference to a seed pack with optional version requirements.
 */
public final class SeedPackRef {

    private final String name;
    private final String specification;
    private final MatchType matchType;
    private final String exactVersion;

    private enum MatchType {
        ANY,
        EXACT,
        RANGE
    }

    private SeedPackRef(String name, String specification, MatchType matchType, String exactVersion) {
        this.name = Objects.requireNonNull(name, "name");
        this.specification = specification;
        this.matchType = Objects.requireNonNull(matchType, "matchType");
        this.exactVersion = exactVersion;
    }

    public String getName() {
        return name;
    }

    public String getSpecification() {
        return specification;
    }

    boolean matches(Semver version) {
        Objects.requireNonNull(version, "version");
        return switch (matchType) {
            case ANY -> true;
            case EXACT -> version.compareTo(Semver.parse(exactVersion)) == 0;
            case RANGE -> version.satisfies(specification);
        };
    }

    public static SeedPackRef of(String name) {
        return new SeedPackRef(name, null, MatchType.ANY, null);
    }

    public static SeedPackRef exact(String name, String version) {
        Objects.requireNonNull(version, "version");
        return new SeedPackRef(name,
                version,
                MatchType.EXACT,
                version);
    }

    public static SeedPackRef range(String name, String range) {
        Objects.requireNonNull(range, "range");
        return new SeedPackRef(name, range.trim(), MatchType.RANGE, null);
    }

    public static SeedPackRef parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Seed pack reference cannot be blank");
        }
        int idx = trimmed.indexOf('@');
        if (idx < 0) {
            return of(trimmed);
        }
        String name = trimmed.substring(0, idx).trim();
        String spec = trimmed.substring(idx + 1).trim();
        if (spec.isEmpty()) {
            return of(name);
        }
        if (spec.startsWith("=") && spec.length() > 1) {
            return exact(name, spec.substring(1));
        }
        if (isExactVersion(spec)) {
            return exact(name, spec);
        }
        return range(name, spec);
    }

    private static boolean isExactVersion(String candidate) {
        try {
            Semver.parse(candidate);
            return !candidate.contains(" ");
        } catch (SemverException ex) {
            return false;
        }
    }

    @Override
    public String toString() {
        return switch (matchType) {
            case ANY -> name;
            case EXACT -> name + "@=" + exactVersion;
            case RANGE -> name + "@" + specification;
        };
    }
}

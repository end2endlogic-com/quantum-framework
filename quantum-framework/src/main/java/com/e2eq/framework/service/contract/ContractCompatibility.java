package com.e2eq.framework.service.contract;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract compatibility rule for generated-SDK handshakes (decision
 * 2026-07-20), borrowing Maven version-range semantics.
 *
 * <p>A client generated against contract {@code M.m.p} effectively declares the
 * Maven range {@code [M.m, (M+1).0)} on the serving application:
 * <ul>
 *   <li>identical spec hash → identical contract → {@link Verdict#IDENTICAL}
 *       (fast path; versions not consulted)</li>
 *   <li>different hash, same major AND server minor ≥ client minor →
 *       {@link Verdict#COMPATIBLE} — the server carries every feature the
 *       client was generated against, assuming the platform discipline that
 *       minor bumps are additive-only (enforced separately in CI by diffing
 *       operation contracts)</li>
 *   <li>different major → {@link Verdict#INCOMPATIBLE}</li>
 *   <li>server minor &lt; client minor → {@link Verdict#SERVICE_OLDER} — the
 *       deployed service predates this SDK; calls may hit missing operations</li>
 * </ul>
 *
 * <p>Version strings are Maven-style {@code major.minor.patch[-qualifier]};
 * qualifiers (e.g. {@code -SNAPSHOT}) are ignored for compatibility. The hash
 * is the fact, the version is the promise: an unparseable or missing version
 * with a differing hash fails closed as {@link Verdict#INCOMPATIBLE}.
 */
public final class ContractCompatibility {

    public enum Verdict {
        IDENTICAL,
        COMPATIBLE,
        SERVICE_OLDER,
        INCOMPATIBLE
    }

    public record Result(Verdict verdict, String message) {
        public boolean allowed() {
            return verdict == Verdict.IDENTICAL || verdict == Verdict.COMPATIBLE;
        }
    }

    private static final Pattern SEMVER = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+].*)?$");

    private ContractCompatibility() {
    }

    public static Result evaluate(String clientHash,
                                  String clientVersion,
                                  String serverHash,
                                  String serverVersion) {
        Objects.requireNonNull(clientHash, "clientHash");
        Objects.requireNonNull(serverHash, "serverHash");
        if (clientHash.equalsIgnoreCase(serverHash)) {
            return new Result(Verdict.IDENTICAL, "Contract hashes match (" + clientHash + ")");
        }

        int[] client = parse(clientVersion);
        int[] server = parse(serverVersion);
        if (client == null || server == null) {
            return new Result(Verdict.INCOMPATIBLE,
                "Contract hashes differ and versions are not comparable (client="
                    + clientVersion + ", server=" + serverVersion
                    + "); regenerate the SDK from the service's current OpenAPI document");
        }

        if (client[0] != server[0]) {
            return new Result(Verdict.INCOMPATIBLE,
                "Contract major versions differ: SDK was generated against " + clientVersion
                    + " but the service serves " + serverVersion
                    + "; a major bump is a breaking change — regenerate and migrate the caller");
        }
        if (server[1] < client[1]) {
            return new Result(Verdict.SERVICE_OLDER,
                "Deployed service contract " + serverVersion + " is OLDER than this SDK ("
                    + clientVersion + "); calls may target operations the service does not have —"
                    + " deploy the newer service or use an SDK generated against " + serverVersion);
        }
        return new Result(Verdict.COMPATIBLE,
            "Contract " + serverVersion + " is newer-or-equal within major " + client[0]
                + " (SDK " + clientVersion + "); additive-only minor policy applies");
    }

    /** Returns {major, minor, patch} or null when unparseable/blank. */
    static int[] parse(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        Matcher m = SEMVER.matcher(version.trim());
        if (!m.matches()) {
            return null;
        }
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), patch};
    }
}

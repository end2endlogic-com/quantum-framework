package com.e2eq.framework.model.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Resolves which application(s) a token should be scoped to at login, from a
 * user's per-(realm) application grant. Pure function — no I/O — so it is
 * exhaustively unit-testable and free of the auth provider's concerns.
 *
 * <p>Decisions this encodes (application-scoped auth, decisions locked 2026-07-12):
 * <ul>
 *   <li><b>Nested (realm, application):</b> the grant is read from the user's
 *       realm membership, so the input here is already realm-specific.</li>
 *   <li><b>Multi-aud SSO:</b> a successful resolution returns the ENTIRE authorized
 *       set as the audiences (the token's {@code aud}); each suite app accepts a
 *       token whose {@code aud} contains its own id, so no re-auth to move between
 *       apps in the realm. The actively-entered app is returned separately as the
 *       {@code azp}.</li>
 *   <li><b>Resolution order:</b> explicit requested app → single authorized app →
 *       configured default → otherwise ambiguous (the caller returns the set so the
 *       login UI can prompt). Origin/host is never consulted.</li>
 *   <li><b>{@code "*"} semantics (decision 2026-07-20):</b> a wildcard grant means
 *       the token is valid for ANY application. It resolves to the literal {@code "*"}
 *       audience — admitted downstream by the shared audience policy ({@code aud}
 *       contains the service's own app id OR {@code "*"}) — rather than forcing a
 *       selection this layer cannot enumerate. {@code azp} records the app actively
 *       entered when one was named. The result is flagged so the caller can
 *       audit-log every wildcard issuance.</li>
 * </ul>
 */
public final class ApplicationAuthorizationResolver {

    public static final String WILDCARD = "*";

    private ApplicationAuthorizationResolver() {
    }

    public enum Outcome {
        /** No application grant configured for this membership — use legacy (single-audience) behavior. */
        LEGACY,
        /** Resolved to a concrete audience set + active application. */
        RESOLVED,
        /** More than one app authorized and none selected — caller should prompt with {@link Result#candidates()}. */
        AMBIGUOUS,
        /** An explicit application was requested that the user is not authorized for. */
        DENIED
    }

    public record Result(Outcome outcome,
                         Set<String> audiences,
                         String activeApplication,
                         List<String> candidates,
                         boolean wildcard,
                         String deniedApplication) {

        public boolean resolved() {
            return outcome == Outcome.RESOLVED;
        }

        static Result legacy() {
            return new Result(Outcome.LEGACY, null, null, List.of(), false, null);
        }

        static Result resolved(Set<String> audiences, String activeApplication, boolean wildcard) {
            return new Result(Outcome.RESOLVED, audiences, activeApplication, List.of(), wildcard, null);
        }

        static Result ambiguous(List<String> candidates, boolean wildcard) {
            return new Result(Outcome.AMBIGUOUS, null, null, List.copyOf(candidates), wildcard, null);
        }

        static Result denied(String deniedApplication) {
            return new Result(Outcome.DENIED, null, null, List.of(), false, deniedApplication);
        }
    }

    /**
     * @param authorizedApplications the membership's grant (may be null/empty, a concrete list, or contain {@code "*"})
     * @param defaultApplication     the app to assume when several are authorized and none is requested (optional)
     * @param requestedApplicationId the app the caller is signing into (optional)
     */
    public static Result resolve(List<String> authorizedApplications,
                                 String defaultApplication,
                                 String requestedApplicationId) {
        return resolve(authorizedApplications, null, defaultApplication, requestedApplicationId);
    }

    /**
     * Resolves a per-realm application list, falling back to the credential-wide
     * application pattern only when the list is absent. Patterns are matched against
     * the complete application id; {@code "*"} is the unrestricted wildcard.
     */
    public static Result resolve(List<String> authorizedApplications,
                                 String applicationRegEx,
                                 String defaultApplication,
                                 String requestedApplicationId) {
        List<String> granted = normalize(authorizedApplications);
        if (granted.isEmpty()) {
            return resolvePattern(applicationRegEx, defaultApplication, requestedApplicationId);
        }

        String requested = trimToNull(requestedApplicationId);
        String defaultApp = trimToNull(defaultApplication);
        boolean wildcard = granted.contains(WILDCARD);

        // Concrete apps that are actually addressable as audiences (wildcard is not an audience).
        LinkedHashSet<String> concrete = new LinkedHashSet<>(granted);
        concrete.remove(WILDCARD);

        if (wildcard) {
            // "*" = valid for any application: mint the wildcard audience instead of
            // demanding a selection this layer cannot enumerate. azp = the app
            // actively entered, when one was named or defaulted.
            return Result.resolved(wildcardAudience(), requested != null ? requested : defaultApp, true);
        }

        if (requested != null) {
            if (concrete.contains(requested)) {
                return Result.resolved(new LinkedHashSet<>(concrete), requested, false);
            }
            return Result.denied(requested);
        }

        if (concrete.size() == 1) {
            String only = concrete.iterator().next();
            return Result.resolved(new LinkedHashSet<>(concrete), only, false);
        }

        if (defaultApp != null && concrete.contains(defaultApp)) {
            return Result.resolved(new LinkedHashSet<>(concrete), defaultApp, false);
        }

        return Result.ambiguous(new ArrayList<>(concrete), false);
    }

    private static Result resolvePattern(String applicationRegEx,
                                         String defaultApplication,
                                         String requestedApplicationId) {
        String expression = trimToNull(applicationRegEx);
        if (expression == null) {
            String requested = trimToNull(requestedApplicationId);
            return requested == null ? Result.legacy() : Result.denied(requested);
        }

        String requested = trimToNull(requestedApplicationId);
        String defaultApp = trimToNull(defaultApplication);

        if (WILDCARD.equals(expression)) {
            // Same "*" semantics as a wildcard grant entry: the token is valid for
            // any application, carried as the literal "*" audience.
            return Result.resolved(wildcardAudience(), requested != null ? requested : defaultApp, true);
        }

        if (requested != null) {
            if (matches(expression, requested)) {
                return Result.resolved(new LinkedHashSet<>(List.of(requested)), requested, false);
            }
            return Result.denied(requested);
        }

        if (defaultApp != null && matches(expression, defaultApp)) {
            return Result.resolved(new LinkedHashSet<>(List.of(defaultApp)), defaultApp, false);
        }

        // A concrete pattern is a deliberate application-scoping decision and can
        // authorize an unbounded set, so callers must name an app.
        return Result.ambiguous(List.of(), false);
    }

    private static LinkedHashSet<String> wildcardAudience() {
        LinkedHashSet<String> aud = new LinkedHashSet<>();
        aud.add(WILDCARD);
        return aud;
    }

    /**
     * True when the application id satisfies the entitlement expression
     * ({@code "*"} = unrestricted; otherwise full-string, case-insensitive
     * regex). Public so callers (e.g. the auth provider) can intersect a
     * pattern with the application registry when enumerating candidates.
     */
    public static boolean matches(String expression, String applicationId) {
        if (WILDCARD.equals(expression)) {
            return true;
        }
        try {
            return Pattern.compile(expression, Pattern.CASE_INSENSITIVE)
                    .matcher(applicationId)
                    .matches();
        } catch (PatternSyntaxException ignored) {
            // Persisted invalid patterns fail closed; validation should reject new ones.
            return false;
        }
    }

    private static List<String> normalize(List<String> apps) {
        if (apps == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String app : apps) {
            String t = trimToNull(app);
            if (t != null) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A requested realm (an {@code X-Realm} header, a resolution request's {@code realm}) read as the
 * registered realm it names. Realm refNames are database names, and MongoDB refuses a database
 * whose name differs from an existing one only in case (error 13297), so a realm named in another
 * case must never reach a datastore lookup as typed: it is resolved to the registered refName.
 *
 * <p>The realm catalog matches a refName ignoring case
 * ({@code LocalSystemDirectory#findRealmByRefName}, which the control plane serves to remote
 * directories), so mixed-case refNames such as a pooled realm's {@code app-P1} resolve from any
 * case. A directory that predates that match is asked again in lower case.</p>
 *
 * <p>Answers are cached per requested name in a bounded map: a found realm for
 * {@link #FOUND_TTL_NANOS}, an unknown one for {@link #MISSING_TTL_NANOS}, so a repeated unknown
 * realm costs no directory call per request. A registration drops the cache
 * ({@link #forget()}, from {@code RealmRefNamesCacheInvalidator}). A directory that cannot answer
 * fails the request typed, 503 {@code PRINCIPAL_RESOLUTION_UNAVAILABLE}, never a 500 and never a
 * guess.</p>
 */
public final class RealmRefNames {

    static final long FOUND_TTL_NANOS = 60_000_000_000L;
    static final long MISSING_TTL_NANOS = 30_000_000_000L;
    static final int MAX_ENTRIES = 1024;

    private record Answer(Optional<String> refName, long expiresAtNanos) {
        boolean fresh(long now) {
            return now - expiresAtNanos < 0;
        }
    }

    private static final Map<String, Answer> CACHE = new ConcurrentHashMap<>();

    private RealmRefNames() {
    }

    /**
     * The registered refName {@code requested} names, ignoring case. Empty when no registered realm
     * matches (or no directory is configured); the caller decides how an unknown realm is refused.
     *
     * @throws RemotePrincipalResolutionException 503 PRINCIPAL_RESOLUTION_UNAVAILABLE when the
     *         directory cannot answer
     */
    public static Optional<String> registered(SystemDirectory directory, String requested) {
        if (directory == null || requested == null || requested.isBlank()) {
            return Optional.empty();
        }
        String trimmed = requested.trim();
        long now = System.nanoTime();
        Answer cached = CACHE.get(trimmed);
        if (cached != null && cached.fresh(now)) {
            return cached.refName();
        }
        Optional<String> answer = lookUp(directory, trimmed);
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(trimmed, new Answer(answer, now + (answer.isPresent() ? FOUND_TTL_NANOS : MISSING_TTL_NANOS)));
        return answer;
    }

    /**
     * {@code requested} as the registered refName when it names one in another case, else
     * unchanged (trimmed).
     */
    public static String canonicalOrSame(SystemDirectory directory, String requested) {
        if (requested == null || requested.isBlank()) {
            return requested;
        }
        return registered(directory, requested).orElse(requested.trim());
    }

    /** Drops every cached answer (a realm was registered or changed). */
    public static void forget() {
        CACHE.clear();
    }

    private static Optional<String> lookUp(SystemDirectory directory, String trimmed) {
        Optional<Realm> found;
        try {
            found = directory.findRealmByRefName(trimmed);
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (found.isEmpty() && !lower.equals(trimmed)) {
                found = directory.findRealmByRefName(lower);
            }
        } catch (RemotePrincipalResolutionException typed) {
            throw typed;
        } catch (RuntimeException outage) {
            throw RemotePrincipalResolutionException.unavailable(
                    "The realm catalog could not be read for a requested realm: " + outage.getClass().getSimpleName());
        }
        return found.map(Realm::getRefName)
                .filter(refName -> refName != null && refName.equalsIgnoreCase(trimmed));
    }
}

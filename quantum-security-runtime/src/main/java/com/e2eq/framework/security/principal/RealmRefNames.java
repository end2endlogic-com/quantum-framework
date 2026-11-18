package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 * <p>A requested name that is not a realm refName's shape ({@link #isRefNameShape}: a MongoDB
 * database name of letters, digits, {@code -} and {@code _}, at most {@link #MAX_REF_NAME_LENGTH}
 * characters) is never looked up: it names no realm.</p>
 *
 * <p>Answers are cached per requested name in bounded least-recently-used maps with a time to
 * live: a found realm for {@link #FOUND_TTL_NANOS}, an unknown one for {@link #MISSING_TTL_NANOS} (5 s, so a realm registered by another service resolves quickly).
 * Found and unknown answers are kept apart, so a flood of unknown names evicts only other unknown
 * names, never a registered realm's answer; a name that is exactly a registered refName answered
 * before is returned without any lookup. A registration drops the cache ({@link #forget()}, from
 * {@code RealmRefNamesCacheInvalidator}). A directory that cannot answer fails the request typed,
 * 503 {@code PRINCIPAL_RESOLUTION_UNAVAILABLE}, never a 500 and never a guess.</p>
 */
public final class RealmRefNames {

    /**
     * Identity attribute {@code RolesAugmentor} sets when the requested realm names no registered
     * realm; {@code SecurityFilter} then refuses the request 403 {@code REALM_NOT_AUTHORIZED}.
     */
    public static final String REFUSED_REALM_ATTRIBUTE = "quantum-realm-refused";

    /**
     * {@link #REFUSED_REALM_ATTRIBUTE}'s value when the catalog could not answer: the request is
     * answered 503 {@code PRINCIPAL_RESOLUTION_UNAVAILABLE}, not 403.
     */
    public static final String CATALOG_UNAVAILABLE = "catalog-unavailable";

    static final long FOUND_TTL_NANOS = 60_000_000_000L;
    static final long MISSING_TTL_NANOS = 5_000_000_000L;
    /** Bound of each answer map (found, unknown). */
    static final int MAX_ENTRIES = 1024;
    /** MongoDB limits a database name to 63 characters. */
    static final int MAX_REF_NAME_LENGTH = 63;

    private static final java.util.regex.Pattern REF_NAME_SHAPE =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0," + (MAX_REF_NAME_LENGTH - 1) + "}");

    private record Answer(Optional<String> refName, long expiresAtNanos) {
        boolean fresh(long now) {
            return now - expiresAtNanos < 0;
        }
    }

    /** A bounded, access-ordered (least recently used first out) map; callers synchronize on it. */
    private static final class Lru extends LinkedHashMap<String, Answer> {
        Lru() {
            super(64, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
            return size() > MAX_ENTRIES;
        }
    }

    private static final Lru FOUND = new Lru();
    private static final Lru MISSING = new Lru();

    private RealmRefNames() {
    }

    /**
     * True when {@code requested} (trimmed) has the shape of a realm refName. A name of any other
     * shape cannot be a registered realm and is never looked up.
     */
    public static boolean isRefNameShape(String requested) {
        return requested != null && REF_NAME_SHAPE.matcher(requested.trim()).matches();
    }

    /**
     * The registered refName {@code requested} names, ignoring case. Empty when no registered realm
     * matches, the name is not a refName's shape, or no directory is configured; the caller decides
     * how an unknown realm is refused.
     *
     * @throws RemotePrincipalResolutionException 503 PRINCIPAL_RESOLUTION_UNAVAILABLE when the
     *         directory cannot answer
     */
    public static Optional<String> registered(SystemDirectory directory, String requested) {
        if (directory == null || !isRefNameShape(requested)) {
            return Optional.empty();
        }
        String trimmed = requested.trim();
        long now = System.nanoTime();
        Answer cached = cached(trimmed, now);
        if (cached != null) {
            return cached.refName();
        }
        Optional<String> answer = lookUp(directory, trimmed);
        Lru target = answer.isPresent() ? FOUND : MISSING;
        Answer fresh = new Answer(answer, now + (answer.isPresent() ? FOUND_TTL_NANOS : MISSING_TTL_NANOS));
        synchronized (target) {
            target.put(trimmed, fresh);
            // The registered refName itself is then known exactly, needing no lookup of its own.
            answer.ifPresent(refName -> target.put(refName, fresh));
        }
        return answer;
    }

    private static Answer cached(String trimmed, long now) {
        for (Lru map : new Lru[] {FOUND, MISSING}) {
            synchronized (map) {
                Answer answer = map.get(trimmed);
                if (answer != null) {
                    if (answer.fresh(now)) {
                        return answer;
                    }
                    map.remove(trimmed);
                }
            }
        }
        return null;
    }

    /** True when {@code trimmed} is exactly a registered refName a fresh answer named. */
    private static boolean knownExactly(String trimmed) {
        long now = System.nanoTime();
        synchronized (FOUND) {
            Answer answer = FOUND.get(trimmed);
            return answer != null && answer.fresh(now) && answer.refName().map(trimmed::equals).orElse(false);
        }
    }

    /**
     * {@code requested} as the registered refName when it names one in another case, else
     * unchanged (trimmed).
     */
    public static String canonicalOrSame(SystemDirectory directory, String requested) {
        if (requested == null || requested.isBlank()) {
            return requested;
        }
        String trimmed = requested.trim();
        if (!isRefNameShape(trimmed) || knownExactly(trimmed)) {
            return trimmed;
        }
        return registered(directory, trimmed).orElse(trimmed);
    }

    /** Drops every cached answer (a realm was registered or changed). */
    public static void forget() {
        synchronized (FOUND) {
            FOUND.clear();
        }
        synchronized (MISSING) {
            MISSING.clear();
        }
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

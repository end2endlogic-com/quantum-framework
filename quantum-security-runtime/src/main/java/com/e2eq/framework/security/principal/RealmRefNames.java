package com.e2eq.framework.security.principal;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.Realm;

import java.util.Locale;
import java.util.Optional;

/**
 * A requested realm (an {@code X-Realm} header, a resolution request's {@code realm}) read as the
 * registered realm it names. Realm refNames are database names, and MongoDB refuses a database
 * whose name differs from an existing one only in case (error 13297), so a realm named in another
 * case must never reach a datastore lookup as typed: it is resolved to the registered refName,
 * compared ignoring case, the way {@code AccessInviteService} reads an invite's realm.
 */
public final class RealmRefNames {

    private RealmRefNames() {
    }

    /**
     * The registered refName {@code requested} names, ignoring case: the exact refName first, then
     * its lower-case form. Empty when no registered realm matches (or the directory is unavailable);
     * the caller decides how an unknown realm is refused.
     */
    public static Optional<String> registered(SystemDirectory directory, String requested) {
        if (directory == null || requested == null || requested.isBlank()) {
            return Optional.empty();
        }
        String trimmed = requested.trim();
        Optional<Realm> found = directory.findRealmByRefName(trimmed);
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (found.isEmpty() && !lower.equals(trimmed)) {
            found = directory.findRealmByRefName(lower);
        }
        return found.map(Realm::getRefName)
                .filter(refName -> refName != null && refName.equalsIgnoreCase(trimmed));
    }

    /**
     * {@code requested} as the registered refName when it names one in another case, else unchanged
     * (trimmed). Only a name with upper-case letters is looked up: registered refNames are lower
     * case, so an all-lower-case name is already the form a datastore lookup can take.
     */
    public static String canonicalOrSame(SystemDirectory directory, String requested) {
        if (requested == null || requested.isBlank()) {
            return requested;
        }
        String trimmed = requested.trim();
        if (trimmed.equals(trimmed.toLowerCase(Locale.ROOT))) {
            return trimmed;
        }
        return registered(directory, trimmed).orElse(trimmed);
    }
}

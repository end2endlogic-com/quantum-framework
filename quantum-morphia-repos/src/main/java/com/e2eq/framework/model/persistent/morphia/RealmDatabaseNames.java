package com.e2eq.framework.model.persistent.morphia;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shape every realm database name has, checked where a datastore is opened. A realm refName is
 * a MongoDB database name: letters, digits, {@code -} and {@code _}, starting with a letter or digit,
 * at most {@value #MAX_LENGTH} characters, compared exactly (no surrounding whitespace, no control
 * characters). MongoDB's own databases ({@code admin}, {@code local}, {@code config}) and the
 * coordination database are never a realm. Configured and registered realms always have this
 * shape, so the check refuses no legitimate flow; whether a realm is registered is decided at the
 * request boundary, not here.
 */
public final class RealmDatabaseNames {

    /** MongoDB limits a database name to 63 characters. */
    public static final int MAX_LENGTH = 63;

    public static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0," + (MAX_LENGTH - 1) + "}");

    /** MongoDB's own databases: never a realm. */
    public static final Set<String> MONGO_RESERVED = Set.of("admin", "local", "config");

    private RealmDatabaseNames() {
    }

    /** True when {@code name}, exactly as given, has a realm refName's shape. */
    public static boolean wellShaped(String name) {
        return name != null && SHAPE.matcher(name).matches();
    }

    /** True when {@code name} (ignoring case and surrounding whitespace) is a database that is never a realm. */
    public static boolean reserved(String name, String coordinationDatabase) {
        if (name == null) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return MONGO_RESERVED.contains(lower)
                || (coordinationDatabase != null && !coordinationDatabase.isBlank()
                    && lower.equals(coordinationDatabase.trim().toLowerCase(Locale.ROOT)));
    }

    /** A realm name no datastore is opened for. */
    public static final class InvalidRealmDatabaseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public InvalidRealmDatabaseException(String message) {
            super(message);
        }
    }
}

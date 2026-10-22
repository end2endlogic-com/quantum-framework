package com.e2eq.framework.api.system;

import java.util.List;

/**
 * Typed refusal of a realm registration that would change a recorded realm in a
 * way a catalog write is not allowed to. Callers branch on {@link #kind()} and
 * report {@link #fields()} instead of parsing the message.
 *
 * <p>Extends {@link IllegalStateException} so existing fail-loud callers keep
 * their semantics.</p>
 */
public class RealmRegistrationConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** The write names a different owning application than the recorded realm. */
        REALM_OWNER_CONFLICT,
        /**
         * The write changes a field that only provisioning may set: database and
         * connection, topology, tenancy, email domain, domain context, or admin.
         */
        REALM_RESTRICTED_FIELD_CHANGE
    }

    private final Kind kind;
    private final String realmRefName;
    private final List<String> fields;

    public RealmRegistrationConflictException(Kind kind, String realmRefName, List<String> fields, String detail) {
        super(kind.name() + ": realm '" + realmRefName + "' " + detail);
        this.kind = kind;
        this.realmRefName = realmRefName;
        this.fields = List.copyOf(fields);
    }

    public Kind kind() {
        return kind;
    }

    public String realmRefName() {
        return realmRefName;
    }

    /** The fields whose requested value differs from the recorded realm. */
    public List<String> fields() {
        return fields;
    }
}

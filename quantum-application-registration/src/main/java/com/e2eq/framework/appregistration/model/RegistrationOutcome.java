package com.e2eq.framework.appregistration.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the environment said back.
 *
 * <p>{@link #getRemainingActions()} is the part that repays the protocol. A
 * service that cannot be served by its environment should say which step is
 * missing and where to run it, rather than starting anyway and failing later
 * inside an unrelated request -- an unmigrated realm surfacing as an
 * authorization error forty minutes after boot is the failure this exists to
 * prevent.</p>
 */
public final class RegistrationOutcome {

    public enum Status {
        /** Registered during this call. */
        REGISTERED,
        /** Already registered; nothing was written. */
        ALREADY_REGISTERED,
        /** The environment is reachable but not ready to serve this application. */
        NOT_READY,
        /** The environment refused this registration outright. */
        REFUSED
    }

    private final Status status;
    private final String detail;
    private final List<String> remainingActions;

    private RegistrationOutcome(Status status, String detail, List<String> remainingActions) {
        this.status = status;
        this.detail = detail;
        this.remainingActions = Collections.unmodifiableList(new ArrayList<>(remainingActions));
    }

    public Status getStatus() { return status; }
    public String getDetail() { return detail; }

    /** Ordered, human-actionable steps that remain; empty when the environment is ready. */
    public List<String> getRemainingActions() { return remainingActions; }

    public boolean isRegistered() {
        return status == Status.REGISTERED || status == Status.ALREADY_REGISTERED;
    }

    public static RegistrationOutcome registered(String detail) {
        return new RegistrationOutcome(Status.REGISTERED, detail, List.of());
    }

    public static RegistrationOutcome alreadyRegistered(String detail) {
        return new RegistrationOutcome(Status.ALREADY_REGISTERED, detail, List.of());
    }

    public static RegistrationOutcome notReady(String detail, List<String> remainingActions) {
        return new RegistrationOutcome(Status.NOT_READY, detail,
                remainingActions == null ? List.of() : remainingActions);
    }

    public static RegistrationOutcome refused(String detail) {
        return new RegistrationOutcome(Status.REFUSED, detail, List.of());
    }

    @Override
    public String toString() {
        return "RegistrationOutcome[" + status + " " + detail
                + (remainingActions.isEmpty() ? "" : " remaining=" + remainingActions) + "]";
    }
}

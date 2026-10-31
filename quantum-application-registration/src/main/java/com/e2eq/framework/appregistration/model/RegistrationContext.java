package com.e2eq.framework.appregistration.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What an application tells the environment about itself when it starts.
 *
 * <p>Every field is configuration, not discovery. A service that infers which
 * environment it is in can register into the wrong one, which is worse than
 * failing to start: the wrong environment's directory then claims a service that
 * is not there, and the right one never learns about it.</p>
 */
public final class RegistrationContext {

    private final String applicationId;
    private final String applicationVersion;
    private final String environmentRef;
    private final Set<String> requiredRealms;
    private final String fingerprint;

    private RegistrationContext(Builder builder) {
        this.applicationId = builder.applicationId;
        this.applicationVersion = builder.applicationVersion;
        this.environmentRef = builder.environmentRef;
        this.requiredRealms = Collections.unmodifiableSet(new LinkedHashSet<>(builder.requiredRealms));
        this.fingerprint = builder.fingerprint != null ? builder.fingerprint : computeFingerprint();
    }

    public String getApplicationId() { return applicationId; }
    public String getApplicationVersion() { return applicationVersion; }
    public String getEnvironmentRef() { return environmentRef; }
    public Set<String> getRequiredRealms() { return requiredRealms; }

    /**
     * Identity of THIS registration, for the cheap already-registered check.
     *
     * <p>It covers everything whose change should force re-registration: the
     * application, its build, the environment, and the realms it needs. A
     * redeploy of the same build into the same environment produces the same
     * fingerprint and costs one local lookup; a version bump or a new realm
     * produces a different one and registers again.</p>
     */
    public String getFingerprint() { return fingerprint; }

    private String computeFingerprint() {
        return String.join("|",
                String.valueOf(applicationId),
                String.valueOf(applicationVersion),
                String.valueOf(environmentRef),
                String.join(",", requiredRealms));
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String applicationId;
        private String applicationVersion;
        private String environmentRef;
        private final Set<String> requiredRealms = new LinkedHashSet<>();
        private String fingerprint;

        public Builder applicationId(String v) { this.applicationId = v; return this; }
        public Builder applicationVersion(String v) { this.applicationVersion = v; return this; }
        public Builder environmentRef(String v) { this.environmentRef = v; return this; }
        public Builder requiredRealm(String v) {
            if (v != null && !v.isBlank()) { this.requiredRealms.add(v.trim()); }
            return this;
        }
        public Builder requiredRealms(Iterable<String> values) {
            if (values != null) { values.forEach(this::requiredRealm); }
            return this;
        }
        /** Override the derived fingerprint; rarely needed. */
        public Builder fingerprint(String v) { this.fingerprint = v; return this; }

        public RegistrationContext build() {
            Objects.requireNonNull(applicationId, "applicationId is required to register");
            Objects.requireNonNull(environmentRef, "environmentRef is required to register");
            return new RegistrationContext(this);
        }
    }

    @Override
    public String toString() {
        return "RegistrationContext[" + applicationId + "@" + environmentRef
                + " version=" + applicationVersion + " realms=" + requiredRealms + "]";
    }
}

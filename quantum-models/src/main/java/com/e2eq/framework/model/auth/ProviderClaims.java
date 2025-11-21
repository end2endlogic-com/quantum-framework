package com.e2eq.framework.model.auth;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral claims DTO produced by auth plugins after validating a token.
 * Framework will turn this into a canonical SecurityIdentity.
 */
@RegisterForReflection
public class ProviderClaims {
    private final String subject; // IdP subject (sub)
    private final Set<String> tokenRoles; // roles/groups asserted by IdP
    private final Map<String, Object> attributes; // pass-through attributes (issuer, username, email, etc.)
    private final String username; // optional human username/handle
    private final String issuer; // optional issuer identifier

    private ProviderClaims(Builder b) {
        this.subject = Objects.requireNonNull(b.subject, "subject (IdP sub) cannot be null");
        this.tokenRoles = Collections.unmodifiableSet(new HashSet<>(b.tokenRoles));
        this.attributes = Collections.unmodifiableMap(new HashMap<>(b.attributes));
        this.username = b.username;
        this.issuer = b.issuer;
    }

    public String getSubject() { return subject; }
    public Set<String> getTokenRoles() { return tokenRoles; }
    public Map<String, Object> getAttributes() { return attributes; }
    public String getUsername() { return username; }
    public String getIssuer() { return issuer; }

    public static Builder builder(String subject) { return new Builder(subject); }

    public static class Builder {
        private final String subject;
        private Set<String> tokenRoles = new HashSet<>();
        private Map<String, Object> attributes = new HashMap<>();
        private String username;
        private String issuer;

        public Builder(String subject) {
            this.subject = subject;
        }

        public Builder tokenRoles(Set<String> roles) {
            if (roles != null) this.tokenRoles = new HashSet<>(roles);
            return this;
        }

        public Builder addRole(String role) {
            if (role != null) this.tokenRoles.add(role);
            return this;
        }

        public Builder attributes(Map<String, Object> attrs) {
            if (attrs != null) this.attributes = new HashMap<>(attrs);
            return this;
        }

        public Builder putAttribute(String key, Object value) {
            if (key != null && value != null) this.attributes.put(key, value);
            return this;
        }

        public Builder username(String username) {
            this.username = username; return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer; return this;
        }

        public ProviderClaims build() { return new ProviderClaims(this); }
    }
}

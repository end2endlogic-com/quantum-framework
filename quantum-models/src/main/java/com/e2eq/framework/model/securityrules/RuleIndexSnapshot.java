package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.security.Rule;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@Data
@NoArgsConstructor
public class RuleIndexSnapshot {
    private boolean enabled;
    private long version;
    private long policyVersion;

    // Legacy flat list for backward compatibility
    private List<RuleEntry> rules = new ArrayList<>();

    // New: identities included when built for a particular request
    private List<String> sources = new ArrayList<>();

    // New: Some rules could not be materialized client-side
    private boolean requiresServer;

    // New: scoped matrices keyed by canonical scope key
    private Map<String, ScopedMatrix> scopes = new HashMap<>();

    // Convenience for clients when they supplied a dataDomain
    private String requestedScope;
    private List<String> requestedFallback;

    @Data
    @NoArgsConstructor
    public static class ScopedMatrix {
        // area -> domain -> action -> outcome
        private Map<String, Map<String, Map<String, Outcome>>> matrix = new HashMap<>();
        private boolean requiresServer;
    }

    @Data
    @NoArgsConstructor
    public static class Outcome {
        private String effect; // ALLOW/DENY
        private String rule;   // rule name
        private int priority;
        private boolean finalRule;
        private String source; // identity that provided the rule (optional)
    }

    @Data
    @NoArgsConstructor
    public static class RuleEntry {
        private String name;
        private String uri; // SecurityURI.getURIString()
        private String effect; // ALLOW/DENY
        private int priority;
        private boolean finalRule;
    }

    public static RuleEntry fromRule(Rule r) {
        RuleEntry e = new RuleEntry();
        e.setName(r.getName());
        e.setUri(r.getSecurityURI() != null ? r.getSecurityURI().uriString() : null);
        e.setEffect(r.getEffect() != null ? r.getEffect().name() : null);
        e.setPriority(r.getPriority());
        e.setFinalRule(r.isFinalRule());
        return e;
    }
}

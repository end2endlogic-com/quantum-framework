package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.security.Rule;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@Data
@NoArgsConstructor
public class RuleIndexSnapshot {
    private boolean enabled;
    private long version;
    private long policyVersion;
    private List<RuleEntry> rules = new ArrayList<>();

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
        e.setUri(r.getSecurityURI() != null ? r.getSecurityURI().getURIString() : null);
        e.setEffect(r.getEffect() != null ? r.getEffect().name() : null);
        e.setPriority(r.getPriority());
        e.setFinalRule(r.isFinalRule());
        return e;
    }
}

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.security.session.SessionSelectionPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;

/** Two applications can supply different pure policies without runtime source changes. */
public final class SelectionPolicyExamples {
    private SelectionPolicyExamples() { }

    @ApplicationScoped
    public static class FixedWorkspace implements SessionSelectionPolicy {
        public static final AtomicInteger calls = new AtomicInteger();
        public String id() { return "fixed-workspace"; }
        public String version() { return "1"; }
        public Selection select(Input input) {
            calls.incrementAndGet();
            return new Selection(input.configuredRealm());
        }
    }

    @ApplicationScoped
    public static class RequestedWorkspace implements SessionSelectionPolicy {
        public static final AtomicInteger calls = new AtomicInteger();
        public String id() { return "requested-workspace"; }
        public String version() { return "1"; }
        public Selection select(Input input) {
            calls.incrementAndGet();
            return new Selection(input.requestedRealm() == null ? input.configuredRealm() : input.requestedRealm());
        }
    }
}

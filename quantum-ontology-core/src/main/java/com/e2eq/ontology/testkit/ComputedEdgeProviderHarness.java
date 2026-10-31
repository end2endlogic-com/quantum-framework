package com.e2eq.ontology.testkit;

import com.e2eq.ontology.core.ComputedEdgeProvider;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.Reasoner;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Test harness for invoking a {@link ComputedEdgeProvider} against a fake
 * environment, then asserting on the produced edges and provenance.
 *
 * <p>Use the static {@link #run} helper for one-shot calls, or instantiate
 * the harness if you want to share a {@code DataDomainInfo} across calls.</p>
 */
public final class ComputedEdgeProviderHarness {

    public static final DataDomainInfo TEST_DOMAIN =
            new DataDomainInfo("testOrg", "0000000001", "testTenant", 0);

    private final String realmId;
    private final DataDomainInfo domain;

    public ComputedEdgeProviderHarness() {
        this("test-realm", TEST_DOMAIN);
    }

    public ComputedEdgeProviderHarness(String realmId, DataDomainInfo domain) {
        this.realmId = realmId;
        this.domain = domain;
    }

    /** Invoke a provider's edges() and return the result. */
    public <S> Result run(ComputedEdgeProvider<S> provider, Object source) {
        List<Reasoner.Edge> edges = provider.edges(realmId, domain, source);
        return new Result(edges);
    }

    /** Static convenience using TEST_DOMAIN. */
    public static <S> Result invoke(ComputedEdgeProvider<S> provider, Object source) {
        return new ComputedEdgeProviderHarness().run(provider, source);
    }

    /** Wrapper around the produced edges with handy assertion helpers. */
    public static final class Result {
        private final List<Reasoner.Edge> edges;

        Result(List<Reasoner.Edge> edges) {
            this.edges = List.copyOf(edges);
        }

        public List<Reasoner.Edge> edges() { return edges; }

        public Set<String> targetIds() {
            return edges.stream().map(Reasoner.Edge::dstId).collect(Collectors.toUnmodifiableSet());
        }

        /** All distinct providerIds recorded in provenance. */
        public Set<String> providerIds() {
            return edges.stream()
                    .flatMap(e -> e.prov().stream())
                    .map(p -> p.inputs() == null ? null : (String) p.inputs().get("providerId"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
        }

        public int size() { return edges.size(); }
        public boolean isEmpty() { return edges.isEmpty(); }
    }
}

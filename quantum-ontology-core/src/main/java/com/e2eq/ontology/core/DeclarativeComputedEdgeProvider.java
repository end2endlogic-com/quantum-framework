package com.e2eq.ontology.core;

import com.e2eq.ontology.annotations.DependsOn.Expand;

import java.util.*;

/**
 * Generic {@link HierarchyListEdgeProvider} driven entirely by a
 * {@link ComputedEdgeSpec} and an application-supplied {@link DeclarativeRuntime}.
 *
 * <p>This is the runtime that backs declarative {@code computed:} YAML entries.
 * One provider instance is created per spec at startup; the framework's
 * cycle/depth/target guards apply automatically because we extend
 * {@code HierarchyListEdgeProvider}.</p>
 */
public final class DeclarativeComputedEdgeProvider<S, H, T>
        extends HierarchyListEdgeProvider<S, H, T> {

    private final ComputedEdgeSpec spec;
    private final DeclarativeRuntime<S, H, T> runtime;

    public DeclarativeComputedEdgeProvider(ComputedEdgeSpec spec, DeclarativeRuntime<S, H, T> runtime) {
        this.spec = Objects.requireNonNull(spec);
        this.runtime = Objects.requireNonNull(runtime);
    }

    @Override public String getProviderId() { return "declarative:" + spec.id(); }
    @Override public Class<S> getSourceType() { return runtime.getSourceType(); }
    @Override public String getPredicate() { return spec.id(); }
    @Override public String getTargetTypeName() { return spec.range(); }
    @Override protected String getHierarchyTypeName() {
        return spec.hierarchy() != null ? spec.hierarchy().nodeType() : "Node";
    }

    @Override protected List<String> getAssignedNodeIds(S source) {
        return runtime.assignedNodeIds(source);
    }

    @Override protected Optional<H> loadHierarchyNode(ComputationContext ctx, String nodeId) {
        return runtime.loadHierarchyNode(nodeId);
    }

    @Override protected List<H> getChildNodes(ComputationContext ctx, String nodeId) {
        Expand expand = spec.hierarchy() != null ? spec.hierarchy().expand() : Expand.NONE;
        if (expand == Expand.NONE) return List.of();
        return runtime.walkHierarchy(nodeId, expand);
    }

    @Override protected List<T> resolveListItems(ComputationContext ctx, H node) {
        return runtime.resolveListItems(node);
    }

    @Override protected String extractTargetId(T target) {
        return runtime.extractTargetId(target);
    }

    /**
     * Dependencies declared in the spec. The application is responsible for
     * resolving the type strings (e.g. "Territory") to actual classes; this
     * method returns the spec's raw strings via the helper below.
     */
    public List<String> declaredDependencyTypeNames() {
        return spec.dependsOnTypes() == null ? List.of() : List.copyOf(spec.dependsOnTypes());
    }

    @Override
    public Set<Class<?>> getDependencyTypes() {
        // The runtime doesn't have access to a class registry. Override in a
        // subclass or wrap with a Set<Class<?>> at registration time if you
        // need automatic dependency tracking by class.
        return Set.of();
    }

    public ComputedEdgeSpec getSpec() { return spec; }
}

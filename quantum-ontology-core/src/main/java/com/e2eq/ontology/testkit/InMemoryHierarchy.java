package com.e2eq.ontology.testkit;

import java.util.*;

/**
 * In-memory hierarchy of nodes keyed by id. Designed for unit tests of
 * {@link com.e2eq.ontology.core.HierarchyListEdgeProvider}.
 *
 * <p>The hierarchy holds:</p>
 * <ul>
 *   <li>nodes — the node objects themselves</li>
 *   <li>parent → children edges (immediate)</li>
 *   <li>node → list-of-targets (the list the node "owns")</li>
 * </ul>
 *
 * <p>Methods return flat descendants on demand, matching the
 * {@code HierarchyListEdgeProvider#getChildNodes} contract.</p>
 *
 * @param <H> hierarchy node type
 * @param <T> target type
 */
public final class InMemoryHierarchy<H, T> {

    public interface IdOf<X> { String id(X x); }

    private final IdOf<H> idOf;
    private final Map<String, H> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> children = new LinkedHashMap<>();
    private final Map<String, List<T>> listItems = new LinkedHashMap<>();

    public InMemoryHierarchy(IdOf<H> idOf) {
        this.idOf = Objects.requireNonNull(idOf);
    }

    /** Register a node. */
    public InMemoryHierarchy<H, T> add(H node) {
        nodes.put(idOf.id(node), node);
        return this;
    }

    /** Register a parent → child relationship. */
    public InMemoryHierarchy<H, T> link(H parent, H child) {
        children.computeIfAbsent(idOf.id(parent), k -> new ArrayList<>()).add(idOf.id(child));
        return this;
    }

    /** Set the list items the given node resolves to. */
    @SafeVarargs
    public final InMemoryHierarchy<H, T> items(H node, T... items) {
        listItems.put(idOf.id(node), new ArrayList<>(Arrays.asList(items)));
        return this;
    }

    public Optional<H> load(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    /** Flat descendants (BFS) of the given node, deduped. */
    public List<H> descendants(String rootId) {
        List<H> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(rootId, List.of()));
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!seen.add(id)) continue;
            H n = nodes.get(id);
            if (n != null) out.add(n);
            queue.addAll(children.getOrDefault(id, List.of()));
        }
        return out;
    }

    public List<T> itemsOf(String nodeId) {
        return listItems.getOrDefault(nodeId, List.of());
    }

    public Set<String> nodeIds() {
        return Collections.unmodifiableSet(nodes.keySet());
    }
}

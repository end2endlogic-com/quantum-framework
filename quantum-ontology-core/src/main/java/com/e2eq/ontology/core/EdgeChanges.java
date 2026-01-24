package com.e2eq.ontology.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks edges that were added, modified, or removed during a reindexing or materialization operation.
 */
public record EdgeChanges(List<EdgeRecord> added, List<EdgeRecord> modified, List<EdgeRecord> removed) {
    public EdgeChanges() {
        this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public static EdgeChanges empty() {
        return new EdgeChanges(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public void addAll(EdgeChanges other) {
        this.added.addAll(other.added);
        this.modified.addAll(other.modified);
        this.removed.addAll(other.removed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
    }
}

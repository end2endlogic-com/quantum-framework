package com.e2eq.ontology.annotations;

/**
 * Cascade policies for relationship properties. All are opt-in; default is NONE.
 */
public enum CascadeType {
    NONE,
    DELETE,           // when the source is deleted, delete the targets
    ORPHAN_REMOVE,    // when target is removed from a collection (or replaced), delete that orphan
    UNLINK,           // ensure edges are removed/unlinked on updates/deletes (edge-only)
    BLOCK_IF_REFERENCED // prevent deleting target if it is referenced by other sources
}

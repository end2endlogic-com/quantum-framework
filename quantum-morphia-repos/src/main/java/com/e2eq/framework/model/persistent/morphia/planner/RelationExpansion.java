package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import org.bson.Document;

import java.util.List;

/**
 * Hydrates {@code expand(@relation)}: for each root row, the targets of a named ontology relation.
 *
 * <p>The repositories and the query gateway live below the ontology modules, so they reach the
 * implementation (in {@code quantum-ontology-mongo}) through this interface and CDI. The caller runs
 * the root query under the root policy first; the implementation reads the page's edges, then the
 * targets under the target type's own row and field policy, and adds them to each row under
 * {@code _rel.<relation>}. Only stable relations may be expanded.</p>
 */
public interface RelationExpansion {

    /** The field under which hydrated relation targets are added to each row. */
    String FIELD = "_rel";

    /**
     * Add the targets of each relation to every row of {@code rows} (documents with an {@code _id}),
     * in place.
     *
     * @throws RelationExpansionException when a relation cannot be expanded; no row is changed
     */
    void hydrate(String realmId, Class<? extends UnversionedBaseModel> rootType, List<Document> rows,
                 List<String> relations);

    /**
     * The installed implementation.
     *
     * @throws RelationExpansionException {@code NOT_AVAILABLE} when the ontology module is not installed
     */
    static RelationExpansion current() {
        try {
            var selection = jakarta.enterprise.inject.spi.CDI.current().select(RelationExpansion.class);
            if (!selection.isUnsatisfied()) {
                return selection.get();
            }
        } catch (IllegalStateException noCdi) {
            // fall through to the typed refusal
        }
        throw new RelationExpansionException(RelationExpansionException.Code.NOT_AVAILABLE, null,
                "expand(@relation) needs the ontology module (quantum-ontology-mongo), which is not installed");
    }

    /** Hydrates {@code relations} on {@code rows} when there are any; a no-op otherwise. */
    static void hydrateIfRequested(String realmId, Class<? extends UnversionedBaseModel> rootType,
                                   List<Document> rows, List<String> relations) {
        if (relations == null || relations.isEmpty()) return;
        current().hydrate(realmId, rootType, rows, relations);
    }
}

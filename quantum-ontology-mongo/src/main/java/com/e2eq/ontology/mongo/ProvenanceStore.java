package com.e2eq.ontology.mongo;

import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.model.OntologyEdgeProvenanceDoc;
import com.e2eq.ontology.repo.OntologyEdgeProvenanceRepo;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

/**
 * Strategy for reading and writing edge provenance.
 *
 * <p>Behavior is gated by config {@code quantum.ontology.provenance.split}:</p>
 * <ul>
 *   <li>{@code false} (default): provenance is inlined on {@link OntologyEdge#getProv()}.
 *       This module is then a thin pass-through over the inline storage so callers
 *       can use the same API regardless of mode.</li>
 *   <li>{@code true}: provenance is written to {@link OntologyEdgeProvenanceDoc}
 *       keyed by edge id. The inline {@code prov} map on {@code OntologyEdge}
 *       remains for legacy edges and a small {@code "providerId"} marker.</li>
 * </ul>
 *
 * <p>Read path checks both: side-collection first, then inline fallback. This lets
 * a deployment switch the flag on without breaking existing inline-provenance edges.</p>
 *
 * <p>NOTE: this PR ships the storage and migration plumbing. Wiring the flag into
 * the {@code OntologyMaterializer} / {@code OntologyEdgeRepo.upsert*} write paths
 * is intentionally a follow-up so it can land with full integration-test coverage.</p>
 */
@ApplicationScoped
public class ProvenanceStore {

    @Inject OntologyEdgeProvenanceRepo provenanceRepo;

    @ConfigProperty(name = "quantum.ontology.provenance.split", defaultValue = "false")
    boolean splitEnabled;

    /** True if the side-collection mode is active for new writes. */
    public boolean isSplitEnabled() { return splitEnabled; }

    /**
     * Persist provenance for an edge.
     *
     * @param realmId    the realm
     * @param edge       the edge being persisted
     * @param providerId provider id (may be null for non-computed edges)
     * @param prov       full provenance map
     */
    public void persist(String realmId, OntologyEdge edge, String providerId, Map<String, Object> prov) {
        if (edge == null) return;
        if (!splitEnabled) {
            edge.setProv(prov);
            return;
        }
        // Side-collection mode: keep a tiny marker on the edge so reads know provenance exists.
        edge.setProv(providerId == null ? Map.of() : Map.of("providerId", providerId, "split", true));
        if (edge.getId() != null) {
            provenanceRepo.upsert(realmId, edge.getId().toString(), providerId, prov);
        } else {
            // Edge not yet saved. Caller should re-invoke after assigning an id.
            Log.debugf("ProvenanceStore.persist called before edge has an id; deferred to caller");
        }
    }

    /**
     * Resolve provenance for an edge, regardless of where it lives.
     */
    public Optional<Map<String, Object>> read(String realmId, OntologyEdge edge) {
        if (edge == null) return Optional.empty();
        // Side collection wins if present.
        if (edge.getId() != null) {
            Optional<OntologyEdgeProvenanceDoc> sideHit = provenanceRepo.findByEdgeId(realmId, edge.getId().toString());
            if (sideHit.isPresent()) {
                return Optional.ofNullable(sideHit.get().getProv());
            }
        }
        return Optional.ofNullable(edge.getProv());
    }
}

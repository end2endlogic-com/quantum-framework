package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyEdgeProvenanceDoc;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for {@link OntologyEdgeProvenanceDoc}.
 *
 * <p>Side-collection storage for computed-edge provenance. See
 * {@code ProvenanceStore} for the read/write strategy that decides whether a
 * given edge's provenance lives inline on {@code OntologyEdge} or here.</p>
 */
@ApplicationScoped
public class OntologyEdgeProvenanceRepo extends MorphiaRepo<OntologyEdgeProvenanceDoc> {

    public Datastore ds() { return ds(getSecurityContextRealmId()); }

    public Datastore ds(String realm) {
        if (realm == null) return morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
        return morphiaDataStoreWrapper.getDataStore(realm);
    }

    public Optional<OntologyEdgeProvenanceDoc> findByEdgeId(String edgeId) {
        return findByEdgeId(getSecurityContextRealmId(), edgeId);
    }

    public Optional<OntologyEdgeProvenanceDoc> findByEdgeId(String realmId, String edgeId) {
        if (edgeId == null || edgeId.isBlank()) return Optional.empty();
        return Optional.ofNullable(ds(realmId).find(OntologyEdgeProvenanceDoc.class)
                .filter(Filters.eq("edgeId", edgeId))
                .first());
    }

    public List<OntologyEdgeProvenanceDoc> findByProviderId(String realmId, String providerId) {
        return ds(realmId).find(OntologyEdgeProvenanceDoc.class)
                .filter(Filters.eq("providerId", providerId))
                .iterator().toList();
    }

    /** Upsert provenance for an edge. */
    public OntologyEdgeProvenanceDoc upsert(String realmId, String edgeId, String providerId, Map<String, Object> prov) {
        Optional<OntologyEdgeProvenanceDoc> existing = findByEdgeId(realmId, edgeId);
        OntologyEdgeProvenanceDoc doc = existing.orElseGet(OntologyEdgeProvenanceDoc::new);
        doc.setEdgeId(edgeId);
        doc.setProviderId(providerId);
        doc.setProv(prov);
        doc.setTs(new Date());
        if (doc.getRefName() == null) doc.setRefName(edgeId);
        return save(ds(realmId), doc);
    }

    public long deleteByEdgeId(String realmId, String edgeId) {
        return ds(realmId).find(OntologyEdgeProvenanceDoc.class)
                .filter(Filters.eq("edgeId", edgeId))
                .delete()
                .getDeletedCount();
    }
}

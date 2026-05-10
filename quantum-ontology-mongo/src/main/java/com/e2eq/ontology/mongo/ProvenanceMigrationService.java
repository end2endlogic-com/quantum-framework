package com.e2eq.ontology.mongo;

import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeProvenanceRepo;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.Datastore;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * One-shot migration from inline-provenance edges to the side
 * {@code ontology_edge_provenance} collection.
 *
 * <p>Idempotent: re-running over already-migrated edges is a no-op (the side
 * collection's unique-by-edgeId index dedupes; the inline {@code prov} clear
 * is conditional on size).</p>
 *
 * <p>Targets only computed/derived edges (those carrying a non-trivial prov
 * map). Non-derived edges generally have small or empty prov and are left in
 * place.</p>
 */
@ApplicationScoped
public class ProvenanceMigrationService {

    @Inject OntologyEdgeRepo edgeRepo;
    @Inject OntologyEdgeProvenanceRepo provenanceRepo;

    public Result migrate(String realmId, boolean dryRun) {
        Result r = new Result();
        Datastore ds = edgeRepo.ds(realmId);
        // Stream all derived edges in the realm.
        List<OntologyEdge> all = ds.find(OntologyEdge.class).iterator().toList();

        for (OntologyEdge edge : all) {
            if (!Boolean.TRUE.equals(edge.isDerived())) continue;
            Map<String, Object> prov = edge.getProv();
            if (prov == null || prov.isEmpty()) continue;
            // Skip if already migrated (marker present).
            if (Boolean.TRUE.equals(prov.get("split"))) {
                r.alreadyMigrated++;
                continue;
            }

            r.candidates++;
            if (dryRun) continue;

            try {
                String edgeId = edge.getId() == null ? null : edge.getId().toString();
                if (edgeId == null) {
                    r.skippedNoId++;
                    continue;
                }
                Object pid = prov.get("providerId");
                String providerId = pid == null ? null : pid.toString();

                provenanceRepo.upsert(realmId, edgeId, providerId, prov);

                // Replace the inline prov with a marker. Keep providerId for filtering.
                Map<String, Object> marker = providerId != null
                        ? Map.of("providerId", providerId, "split", true)
                        : Map.of("split", true);
                edge.setProv(marker);
                edgeRepo.save(ds, edge);
                r.migrated++;
            } catch (Exception e) {
                Log.warnf(e, "Provenance migration failed for edge %s", edge.getId());
                r.errors++;
            }
        }
        return r;
    }

    public static final class Result {
        public int candidates;
        public int migrated;
        public int alreadyMigrated;
        public int skippedNoId;
        public int errors;
        public boolean dryRun;
    }
}

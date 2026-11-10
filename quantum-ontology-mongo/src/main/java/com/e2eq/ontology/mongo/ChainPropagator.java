package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

/**
 * Write-triggered backward propagation of property chains (P2).
 *
 * <p>A chain {@code [p1, ..., pn] => q} is materialized at its head: the entity X that starts the
 * path. {@link OntologyMaterializer#apply} re-derives X only when X itself is written. When an
 * entity further along the path changes its edges, for example a POLine linking to a Shipment
 * under {@code [hasPO, hasLine, lineShipment] => hasShipment}, the head's derived edges would stay
 * stale until X was saved again. This class closes that gap.</p>
 *
 * <p>Given the edges that changed at a node N (added, modified or removed, with N as their source),
 * it finds every chain step {@code pk} (k &gt; 1) those edges use, walks backwards from N over the
 * incoming {@code pk-1}, ..., {@code p1} edges to the chain heads, and re-derives each head with
 * {@link OntologyMaterializer#rederive}, which also prunes the head's inferred edges that are no
 * longer justified. A head whose own derived edges change is propagated in turn, since its new
 * edges may be steps of further chains. Step k = 1 needs no walk: N is the head and the caller has
 * just re-derived it (or, after a delete, has removed it).</p>
 *
 * <p>It runs only from writes (the post-persist and post-delete hooks), never on a schedule. The
 * number of re-derivations per write is bounded by
 * {@code quantum.ontology.chain-propagation.max-rederivations}; reaching the bound is logged as an
 * error naming what was left, and DriftRepairJob or a reindex repairs the rest.</p>
 *
 * <p>Known limit: a head is re-derived against its neighbours' stored inferred edges. In a cyclic
 * graph under a recursive chain (its implied predicate is also one of its steps), two heads can keep
 * justifying each other's stale edge after the edge that supported both is removed. Removing that
 * needs delete-and-rederive over the affected heads, which this class does not do; a reindex clears
 * it. Transitive chain steps need no backward closure here because chain validation rejects them.</p>
 */
@ApplicationScoped
public class ChainPropagator {

    /** Summary of one propagation. */
    public record Result(int rederived, int changedEdges, boolean truncated, Set<String> pendingHeads) {}

    @Inject
    OntologyMaterializer materializer;

    @Inject
    OntologyEdgeRepo edgeRepo;

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    @ConfigProperty(name = "quantum.ontology.chain-propagation.max-rederivations", defaultValue = "1000")
    int maxRederivations;

    public ChainPropagator() {
    }

    ChainPropagator(OntologyMaterializer materializer, OntologyEdgeRepo edgeRepo,
                    TenantOntologyRegistryProvider registryProvider, int maxRederivations) {
        this.materializer = materializer;
        this.edgeRepo = edgeRepo;
        this.registryProvider = registryProvider;
        this.maxRederivations = maxRederivations;
    }

    /**
     * Propagate the edge changes {@code changes} made at {@code nodeId} to the chain heads upstream
     * of it. Changed edges at other nodes (the inverse or symmetric images of {@code nodeId}'s edges)
     * also start a walk there, and make that node a head itself when they start a chain.
     */
    public Result propagate(String realmId, DataDomain dataDomain, String nodeId, EdgeChanges changes) {
        Map<String, Set<String>> bySrc = new LinkedHashMap<>();
        bySrc.put(nodeId, changedPredicates(nodeId, changes));
        Map<String, String> srcTypes = new LinkedHashMap<>();
        changesElsewhere(nodeId, changes, bySrc, srcTypes);
        return propagate(realmId, dataDomain, nodeId, bySrc, srcTypes);
    }

    /**
     * Propagate a change of the given predicates at {@code nodeId}, for callers that know what
     * changed without an {@link EdgeChanges}, such as a delete that removed all of a node's edges.
     */
    public Result propagate(String realmId, DataDomain dataDomain, String nodeId, Set<String> changedPredicates) {
        return propagate(realmId, dataDomain, nodeId,
                changedPredicates == null ? Map.of() : Map.of(nodeId, changedPredicates), Map.of());
    }

    private Result propagate(String realmId, DataDomain dataDomain, String nodeId,
                             Map<String, Set<String>> changedBySrc, Map<String, String> otherTypes) {
        changedBySrc = new LinkedHashMap<>(changedBySrc);
        changedBySrc.values().removeIf(Set::isEmpty);
        if (dataDomain == null || nodeId == null || changedBySrc.isEmpty()) {
            return new Result(0, 0, false, Set.of());
        }
        OntologyRegistry registry = registryProvider.getRegistryForTenant(dataDomain);
        List<PropertyChainDef> chains = registry.propertyChains() == null ? List.of() : registry.propertyChains();

        // Work queue of (node, predicates that changed at it). A head is queued once at a time; if
        // it changes again while queued, its predicates are merged.
        Map<String, Set<String>> pending = new LinkedHashMap<>();
        changedBySrc.forEach((node, ps) -> pending.put(node, new LinkedHashSet<>(ps)));
        Set<String> origin = Set.of(nodeId);
        // Other nodes whose edges changed (inverse and symmetric images) were not materialized: when a
        // rule consumes a changed predicate there, they are re-derived themselves
        Map<String, String> selfHeads = new LinkedHashMap<>();
        changedBySrc.forEach((node, ps) -> {
            if (!node.equals(nodeId) && ps.stream().anyMatch(q -> OntologyMaterializer.consumedByRules(registry, q))) {
                selfHeads.put(node, otherTypes.get(node));
            }
        });
        if (chains.isEmpty() && selfHeads.isEmpty()) {
            return new Result(0, 0, false, Set.of());
        }
        int rederived = 0;
        int changedEdges = 0;

        while (!pending.isEmpty()) {
            Iterator<Map.Entry<String, Set<String>>> it = pending.entrySet().iterator();
            Map.Entry<String, Set<String>> next = it.next();
            it.remove();

            Map<String, String> heads = new LinkedHashMap<>();
            if (selfHeads.containsKey(next.getKey())) {
                heads.put(next.getKey(), selfHeads.remove(next.getKey()));
            }
            headsUpstream(realmId, dataDomain, next.getKey(), next.getValue(), chains).forEach(heads::putIfAbsent);
            Iterator<Map.Entry<String, String>> headIt = heads.entrySet().iterator();
            while (headIt.hasNext()) {
                Map.Entry<String, String> head = headIt.next();
                String headId = head.getKey();
                if (origin.contains(headId) && next.getKey().equals(headId)) {
                    continue; // the written entity was just re-derived by its own materialization
                }
                if (rederived >= maxRederivations) {
                    Set<String> left = new LinkedHashSet<>(pending.keySet());
                    left.add(headId);
                    headIt.forEachRemaining(rest -> left.add(rest.getKey()));
                    Log.errorf("Chain propagation from %s stopped after %d re-derivations "
                                    + "(quantum.ontology.chain-propagation.max-rederivations); derived edges of %d "
                                    + "chain heads may be stale until DriftRepairJob or a reindex runs: %s",
                            nodeId, rederived, left.size(), left);
                    return new Result(rederived, changedEdges, true, left);
                }
                EdgeChanges headChanges = materializer.rederive(realmId, dataDomain, headId, head.getValue());
                rederived++;
                changedEdges += headChanges.added().size() + headChanges.modified().size() + headChanges.removed().size();
                Set<String> headPredicates = changedPredicates(headId, headChanges);
                if (!headPredicates.isEmpty()) {
                    pending.computeIfAbsent(headId, __ -> new LinkedHashSet<>()).addAll(headPredicates);
                }
                // Images the head wrote on other nodes propagate from there
                Map<String, Set<String>> elsewhere = new LinkedHashMap<>();
                Map<String, String> elsewhereTypes = new LinkedHashMap<>();
                changesElsewhere(headId, headChanges, elsewhere, elsewhereTypes);
                elsewhere.forEach((node, ps) -> {
                    pending.computeIfAbsent(node, __ -> new LinkedHashSet<>()).addAll(ps);
                    if (!origin.contains(node) && ps.stream().anyMatch(q -> OntologyMaterializer.consumedByRules(registry, q))) {
                        selfHeads.putIfAbsent(node, elsewhereTypes.get(node));
                    }
                });
            }
        }
        return new Result(rederived, changedEdges, false, Set.of());
    }

    /**
     * Chain heads upstream of {@code nodeId} for chains that use one of {@code predicates} at a step
     * after the first, mapped to the head's type (from the edge that leaves it).
     */
    Map<String, String> headsUpstream(String realmId, DataDomain dataDomain, String nodeId,
                                      Set<String> predicates, List<PropertyChainDef> chains) {
        Map<String, String> heads = new LinkedHashMap<>();
        for (PropertyChainDef chain : chains) {
            List<String> steps = chain.chain();
            if (steps == null) continue;
            for (int k = 1; k < steps.size(); k++) {
                if (!predicates.contains(steps.get(k))) continue;
                // Walk backwards over steps k-1 .. 0 from nodeId
                Map<String, String> frontier = new LinkedHashMap<>();
                frontier.put(nodeId, null);
                for (int j = k - 1; j >= 0 && !frontier.isEmpty(); j--) {
                    Map<String, String> previous = new LinkedHashMap<>();
                    for (String node : frontier.keySet()) {
                        for (OntologyEdge in : edgeRepo.findByDstAndP(realmId, dataDomain, node, steps.get(j))) {
                            previous.putIfAbsent(in.getSrc(), in.getSrcType());
                        }
                    }
                    frontier = previous;
                }
                frontier.forEach(heads::putIfAbsent);
            }
        }
        return heads;
    }

    /** Changed edges whose source is not {@code nodeId}, grouped by source, with each source's type. */
    private static void changesElsewhere(String nodeId, EdgeChanges changes, Map<String, Set<String>> bySrc,
                                         Map<String, String> srcTypes) {
        if (changes == null) return;
        for (List<EdgeRecord> list : List.of(changes.added(), changes.modified(), changes.removed())) {
            for (EdgeRecord rec : list) {
                if (rec == null || rec.getSrc() == null || rec.getSrc().equals(nodeId)) continue;
                bySrc.computeIfAbsent(rec.getSrc(), __ -> new LinkedHashSet<>()).add(rec.getP());
                srcTypes.putIfAbsent(rec.getSrc(), rec.getSrcType());
            }
        }
    }

    private static Set<String> changedPredicates(String nodeId, EdgeChanges changes) {
        Set<String> out = new LinkedHashSet<>();
        if (changes == null) return out;
        for (List<EdgeRecord> list : List.of(changes.added(), changes.modified(), changes.removed())) {
            for (EdgeRecord rec : list) {
                if (rec != null && Objects.equals(rec.getSrc(), nodeId)) {
                    out.add(rec.getP());
                }
            }
        }
        return out;
    }
}

package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * A raw ingest edge that could NOT be governed into a DataDomain placement and was therefore
 * routed to quarantine instead of being written to the {@code edges} collection (S3).
 *
 * <p>Fail-closed contract: the governed ingest path ({@link com.e2eq.ontology.repo.OntologyEdgeRepo#ingestEdges})
 * NEVER synthesizes a default/{@code __unowned} DataDomain for an unplaceable row. The row's raw
 * triple, the source it came from, the attributes that were attempted, and the failing policy
 * key + reason are captured here so the placement can be diagnosed and (later, S7) replayed once
 * the source policy or the row is corrected. Deliberately carries NO {@code dataDomain} field —
 * a quarantined edge has no governed placement by definition.</p>
 *
 * <p><b>Access scoping (decision):</b> this is an <b>ADMIN-only</b> queue. It is realm-partitioned
 * (persisted under the active security-context realm, not in {@code edges}) but intentionally
 * <b>domainless</b>, so it has no org/account/tenant row scoping within a realm. Because
 * {@link #attemptedAttributes} can contain tenant-identifying values from a row whose tenant could
 * NOT be resolved, access MUST be gated by the {@code edge_quarantine} functional domain
 * ({@link #bmFunctionalDomain()}) granted only to admin/system identities. The S7 replay path
 * (re-driving quarantined rows) MUST NOT be exposed until that permission rule is wired.</p>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "ontology_edge_quarantine", useDiscriminator = false)
public class QuarantinedEdge extends UnversionedBaseModel {

    // Raw edge triple as presented by the source.
    private String src;
    private String srcType;
    private String p;
    private String dst;
    private String dstType;

    // Provenance of the failed ingest.
    private String sourceId;
    private String entityType;

    // The attributes that were offered to the resolver (the ingest row values + source metadata).
    private Map<String, Object> attemptedAttributes;

    // Why placement failed. policyKey is the most-specific key that was attempted (best-effort,
    // for diagnosis); reason is the resolver's Unresolvable reason. (S7 lineage block defers; we
    // capture key+reason now so it can be wired later.)
    private String policyKey;
    private String reason;

    private Date quarantinedAt;

    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }

    @Override
    public String bmFunctionalDomain() {
        return "edge_quarantine";
    }
}

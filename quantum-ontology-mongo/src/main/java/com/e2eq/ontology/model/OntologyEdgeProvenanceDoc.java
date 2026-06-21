package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

/**
 * Side-collection document holding the per-edge provenance map.
 *
 * <p>When {@code quantum.ontology.provenance.split=true}, computed-edge provenance
 * (hierarchy paths, resolved lists) is written here keyed by {@code edgeId} rather
 * than inlined on {@link OntologyEdge#getProv()}. This keeps the working set of
 * the primary edges collection small for high-fanout providers, and lets large
 * provenance trails be loaded only on demand.</p>
 *
 * <p>The {@code providerId} field is denormalized for indexed filtering ("show me
 * all provenance for AssociateCanSeeLocationProvider").</p>
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "ontology_edge_provenance", useDiscriminator = false)
@Indexes({
    @Index(options = @IndexOptions(name = "uniq_edgeId", unique = true),
           fields = { @Field("edgeId") }),
    @Index(options = @IndexOptions(name = "idx_providerId"),
           fields = { @Field("providerId") })
})
public class OntologyEdgeProvenanceDoc extends UnversionedBaseModel {

    /** ID of the OntologyEdge this provenance describes. */
    protected String edgeId;

    /** Convenience denormalization for filtering. */
    protected String providerId;

    /** The provenance payload (hierarchyPath, resolvedLists, computedAt, etc.). */
    protected Map<String, Object> prov;

    protected Date ts;

    @Override
    public String bmFunctionalArea() { return "ontology"; }

    @Override
    public String bmFunctionalDomain() { return "edge_provenance"; }
}

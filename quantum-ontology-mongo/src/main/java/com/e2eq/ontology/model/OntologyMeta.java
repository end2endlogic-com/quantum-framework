package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.Field;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * OntologyMeta is a GLOBAL singleton (one per deployment, not per realm).
 * The ontology TBox is shared across all realms; only ABox (instance edges) are realm-scoped.
 * Singleton enforced by unique index on refName="global".
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "ontology_meta")
@Indexes({
    @Index(options = @IndexOptions(name = "idx_meta_singleton", unique = true), fields = { @Field("refName") })
})
public class OntologyMeta extends UnversionedBaseModel {

    private String yamlHash;      // SHA-256 of YAML source (last applied)
    private String tboxHash;      // SHA-256 of canonicalized TBox (last applied)
    private Integer yamlVersion;  // version number from YAML (last applied)
    private String source;        // classpath or file path (last observed)
    private Date updatedAt;       // last observation time
    private Date appliedAt;       // when yamlHash/tboxHash were last applied
    private boolean reindexRequired; // flag: edges should be recomputed due to ontology change

    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }

    @Override
    public String bmFunctionalDomain() {
        return "meta";
    }
}

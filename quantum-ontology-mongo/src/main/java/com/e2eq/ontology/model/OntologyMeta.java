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

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "ontology_meta")
@Indexes({
    @Index(options = @IndexOptions(name = "idx_meta_ref"), fields = { @Field("refName") })
})
public class OntologyMeta extends UnversionedBaseModel {

    private String yamlHash;      // SHA-256 of YAML source (last applied)
    private Integer yamlVersion;  // optional: version number from YAML, if provided
    private String source;        // classpath or file path used (last observed)
    private Date updatedAt;       // last observed time
    private Date appliedAt;       // when yamlHash was last applied
    private boolean reindexRequired; // flag indicating edges should be recomputed due to ontology change

    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }

    @Override
    public String bmFunctionalDomain() {
        return "meta";
    }
}

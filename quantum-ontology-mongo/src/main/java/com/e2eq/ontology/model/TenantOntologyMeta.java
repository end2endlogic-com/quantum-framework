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
 * Tenant-specific ontology metadata. Each tenant has its own ontology version.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "tenant_ontology_meta")
@Indexes({
    // Unique per tenant - each tenant has one active ontology
    @Index(options = @IndexOptions(name = "idx_tenant_meta_unique", unique = true), 
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment")
           }),
    // Query by tenant
    @Index(options = @IndexOptions(name = "idx_tenant_meta_lookup"), 
           fields = {
               @Field("dataDomain.tenantId"),
               @Field("active")
           })
})
public class TenantOntologyMeta extends UnversionedBaseModel {

    private String yamlHash;      // SHA-256 of YAML source (last applied)
    private String tboxHash;      // SHA-256 of canonicalized TBox (last applied)
    private Integer yamlVersion;  // version number from YAML (last applied)
    private String source;        // classpath or file path (last observed)
    private Date updatedAt;       // last observation time
    private Date appliedAt;       // when yamlHash/tboxHash were last applied
    private boolean reindexRequired; // flag: edges should be recomputed due to ontology change
    private boolean active;       // whether this is the active ontology for the tenant
    private String softwareVersion; // software version this ontology is compatible with

    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }

    @Override
    public String bmFunctionalDomain() {
        return "tenant-meta";
    }
}
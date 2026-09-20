package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "edges", useDiscriminator = false)
@Indexes({
    // DataDomain-scoped unique index: prevents collisions across orgs/accounts within same tenant
    @Index(options = @IndexOptions(name = "uniq_domain_src_p_dst", unique = true),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("src"),
               @Field("p"),
               @Field("dst")
           }),
    // Read-optimizing index for finding edges by predicate and destination (e.g., "who has role X?")
    @Index(options = @IndexOptions(name = "idx_domain_p_dst"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("p"),
               @Field("dst")
           }),
    // Read-optimizing index for finding edges by source and predicate (e.g., "what roles does entity X have?")
    @Index(options = @IndexOptions(name = "idx_domain_src_p"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("src"),
               @Field("p")
           }),
    // Index for finding derived edges within a DataDomain
    @Index(options = @IndexOptions(name = "idx_domain_derived"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("derived")
           }),
    // Index for findByDst() queries - prevents collection scan
    @Index(options = @IndexOptions(name = "idx_domain_dst"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("dst")
           }),
    // Index for efficient inferred edge pruning during reindex
    @Index(options = @IndexOptions(name = "idx_domain_src_inferred"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("src"),
               @Field("inferred")
           }),
    // Read-optimizing index for edge traversal queries with property filters on destination
    @Index(options = @IndexOptions(name = "idx_domain_p_dst_props"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("p"),
               @Field("dst"),
               @Field("props")
           }),
    // Read-optimizing index for edge traversal queries with property filters on source
    @Index(options = @IndexOptions(name = "idx_domain_src_p_props"),
           fields = {
               @Field("dataDomain.orgRefName"),
               @Field("dataDomain.accountNum"),
               @Field("dataDomain.tenantId"),
               @Field("dataDomain.dataSegment"),
               @Field("src"),
               @Field("p"),
               @Field("props")
           })
})
public class OntologyEdge extends UnversionedBaseModel {

    protected String src;
    protected String srcType;
    protected String p;
    protected String dst;
    protected String dstType;
    protected boolean inferred;
    protected boolean derived; // true for implied edges
    protected Map<String, Object> props; // open edge properties map (qualifiers, roles, status)
    protected Map<String, Object> prov;
    protected List<Support> support; // provenance support for derived edges
    protected Date ts;

    public Object getProperty(String key) {
        return props != null ? props.get(key) : null;
    }

    public void setProperty(String key, Object val) {
        if (props == null) {
            props = new java.util.HashMap<>();
        }
        props.put(key, val);
    }

    public boolean hasProperty(String key) {
        return props != null && props.containsKey(key);
    }

    @Data
    @NoArgsConstructor
    public static class Support {
        private String ruleId;
        private List<String> pathEdgeIds;
    }

    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }

    @Override
    public String bmFunctionalDomain() {
        return "edges";
    }
}

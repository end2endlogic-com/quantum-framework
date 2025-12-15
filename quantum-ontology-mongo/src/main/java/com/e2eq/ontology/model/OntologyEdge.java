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
@Entity(value = "edges")
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
    protected Map<String, Object> prov;
    protected List<Support> support; // provenance support for derived edges
    protected Date ts;

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

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
    @Index(options = @IndexOptions(name = "idx_tenant_p_dst"),
           fields = { @Field("dataDomain.tenantId"), @Field("p"), @Field("dst") }),
    @Index(options = @IndexOptions(name = "idx_tenant_src_p"),
           fields = { @Field("dataDomain.tenantId"), @Field("src"), @Field("p") }),
    @Index(options = @IndexOptions(name = "idx_tenant_derived"),
           fields = { @Field("dataDomain.tenantId"), @Field("derived") }),
    @Index(options = @IndexOptions(name = "uniq_tenant_src_p_dst", unique = true),
           fields = { @Field("dataDomain.tenantId"), @Field("src"), @Field("p"), @Field("dst") })
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

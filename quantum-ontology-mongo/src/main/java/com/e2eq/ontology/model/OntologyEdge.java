package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.Indexed;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.Map;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "edges")
@Indexes({
    @Index(options = @IndexOptions(name = "idx_tenant_p_dst"),
           fields = { @Field("dataDomain.tenantId"), @Field("p"), @Field("dst") }),
    @Index(options = @IndexOptions(name = "idx_tenant_src_p"),
           fields = { @Field("dataDomain.tenantId"), @Field("src"), @Field("p") })
})
public class OntologyEdge extends UnversionedBaseModel {

    protected String src;
    protected String srcType;
    protected String p;
    protected String dst;
    protected String dstType;
    protected boolean inferred;
    protected Map<String, Object> prov;
    protected Date ts;

    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }

    @Override
    public String bmFunctionalDomain() {
        return "edges";
    }
}

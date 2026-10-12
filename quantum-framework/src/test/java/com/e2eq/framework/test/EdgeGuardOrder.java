package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** Edge source for OntologyEdgeWriteFilterTest: saving one materializes a placedBy edge. */
@Entity("edgeGuardOrders")
@OntologyClass(id = "EdgeGuardOrder")
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class EdgeGuardOrder extends BaseModel {

    @Reference
    @OntologyProperty(id = "placedBy", functional = true)
    protected EdgeGuardCustomer placedBy;

    @Override
    public String bmFunctionalArea() {
        return "EDGEGUARD";
    }

    @Override
    public String bmFunctionalDomain() {
        return "ORDER";
    }
}

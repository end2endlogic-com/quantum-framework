package com.e2eq.framework.test;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** Edge target for OntologyEdgeWriteFilterTest. */
@Entity("edgeGuardCustomers")
@OntologyClass(id = "EdgeGuardCustomer")
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
public class EdgeGuardCustomer extends BaseModel {

    @Override
    public String bmFunctionalArea() {
        return "EDGEGUARD";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CUSTOMER";
    }
}

package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import dev.morphia.annotations.Entity;

@Entity(value = "it_orders")
@OntologyClass(id = "Order")
public class ItOrder extends UnversionedBaseModel {

    @OntologyProperty(id = "placedBy", inverseOf = "placed", functional = true)
    private ItCustomer placedBy;

    @OntologyProperty(id = "placedInOrg", subPropertyOf = {"inOrg"}, functional = true)
    private ItOrg placedInOrg;

    public ItCustomer getPlacedBy() { return placedBy; }
    public void setPlacedBy(ItCustomer placedBy) { this.placedBy = placedBy; }

    public ItOrg getPlacedInOrg() { return placedInOrg; }
    public void setPlacedInOrg(ItOrg placedInOrg) { this.placedInOrg = placedInOrg; }

    @Override
    public String bmFunctionalArea() { return "it"; }

    @Override
    public String bmFunctionalDomain() { return "orders"; }
}

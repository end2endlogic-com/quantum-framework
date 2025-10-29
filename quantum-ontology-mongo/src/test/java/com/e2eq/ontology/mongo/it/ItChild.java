package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;

@Entity(value = "it_children")
@OntologyClass(id = "Child")
public class ItChild extends UnversionedBaseModel {

    @Override
    public String bmFunctionalArea() { return "it"; }

    @Override
    public String bmFunctionalDomain() { return "children"; }
}

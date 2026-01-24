package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;

/**
 * Test entity ClassA for chain inference testing.
 * A -> B via "refersToB" property.
 * Chain inference should create A -> C via "refersToC" when B -> C exists.
 */
@Entity(value = "chain_class_a")
@OntologyClass(id = "ChainClassA")
public class ChainClassA extends UnversionedBaseModel {

    @Reference
    @OntologyProperty(id = "refersToB", functional = true)
    private ChainClassB refersToB;

    public ChainClassB getRefersToB() {
        return refersToB;
    }

    public void setRefersToB(ChainClassB refersToB) {
        this.refersToB = refersToB;
    }

    @Override
    public String bmFunctionalArea() {
        return "chain-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "class-a";
    }
}

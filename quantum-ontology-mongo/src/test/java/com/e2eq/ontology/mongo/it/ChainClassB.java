package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Reference;

/**
 * Test entity ClassB for chain inference testing.
 * B -> C via "refersToC" property.
 * B -> D via "refersToD" property (used for reindex test).
 */
@Entity(value = "chain_class_b")
@OntologyClass(id = "ChainClassB")
public class ChainClassB extends UnversionedBaseModel {

    @Reference
    @OntologyProperty(id = "refersToC", functional = true)
    private ChainClassC refersToC;

    @Reference
    @OntologyProperty(id = "refersToD", functional = true)
    private ChainClassD refersToD;

    public ChainClassC getRefersToC() {
        return refersToC;
    }

    public void setRefersToC(ChainClassC refersToC) {
        this.refersToC = refersToC;
    }

    public ChainClassD getRefersToD() {
        return refersToD;
    }

    public void setRefersToD(ChainClassD refersToD) {
        this.refersToD = refersToD;
    }

    @Override
    public String bmFunctionalArea() {
        return "chain-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "class-b";
    }
}

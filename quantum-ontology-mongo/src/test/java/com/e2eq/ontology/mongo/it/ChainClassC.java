package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;

/**
 * Test entity ClassC for chain inference testing.
 * Terminal class in A -> B -> C chain.
 */
@Entity(value = "chain_class_c")
@OntologyClass(id = "ChainClassC")
public class ChainClassC extends UnversionedBaseModel {

    @Override
    public String bmFunctionalArea() {
        return "chain-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "class-c";
    }
}

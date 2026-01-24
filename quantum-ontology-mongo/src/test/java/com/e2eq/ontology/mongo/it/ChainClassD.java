package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import dev.morphia.annotations.Entity;

/**
 * Test entity ClassD for reindex testing.
 * Used to test out-of-band edge creation: B -> D added later,
 * then reindex should discover and create A -> D inferred edge.
 */
@Entity(value = "chain_class_d")
@OntologyClass(id = "ChainClassD")
public class ChainClassD extends UnversionedBaseModel {

    @Override
    public String bmFunctionalArea() {
        return "chain-test";
    }

    @Override
    public String bmFunctionalDomain() {
        return "class-d";
    }
}

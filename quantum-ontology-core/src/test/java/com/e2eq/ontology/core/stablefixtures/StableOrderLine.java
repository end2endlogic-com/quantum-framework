package com.e2eq.ontology.core.stablefixtures;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;

/** Declares {@code ownedBy} again without stability: the merge rule keeps it stable. */
@OntologyClass(id = "StableOrderLine")
public class StableOrderLine {
    @OntologyProperty(id = "ownedBy", range = "StableOrder")
    private String owner;
}

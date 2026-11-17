package com.e2eq.ontology.core.stablefixtures;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;

/** Annotation-loader fixture for the relation contract fields. */
@OntologyClass(id = "StableOrder")
public class StableOrder {
    @OntologyProperty(id = "ownedBy", range = "StableOrder", stability = "Stable")
    private String owner;

    @OntologyProperty(id = "derivedLink", range = "StableOrder", derivation = "derived")
    private String derivedLink;
}

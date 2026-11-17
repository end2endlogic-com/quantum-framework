package com.e2eq.ontology.core.brokenfixtures;

import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;

/** Annotation-loader fixture with an unknown stability value. */
@OntologyClass(id = "BrokenOrder")
public class BrokenOrder {
    @OntologyProperty(id = "broken", range = "BrokenOrder", stability = "sometimes")
    private String broken;
}

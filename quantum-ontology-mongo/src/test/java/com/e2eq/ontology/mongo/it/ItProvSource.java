package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import dev.morphia.annotations.Entity;

@Entity(value = "it_prov_sources")
@OntologyClass(id = "ProvSource")
public class ItProvSource extends UnversionedBaseModel {

    // Annotation-driven explicit edge
    @OntologyProperty(id = "annRel", ref = "ProvTgt", functional = true)
    private ItProvTarget annotatedTarget;

    // Provider-driven extra relation (not annotated)
    private String providerTargetRef;

    public ItProvTarget getAnnotatedTarget() { return annotatedTarget; }
    public void setAnnotatedTarget(ItProvTarget annotatedTarget) { this.annotatedTarget = annotatedTarget; }

    public String getProviderTargetRef() { return providerTargetRef; }
    public void setProviderTargetRef(String providerTargetRef) { this.providerTargetRef = providerTargetRef; }

    @Override
    public String bmFunctionalArea() { return "it"; }

    @Override
    public String bmFunctionalDomain() { return "prov"; }
}

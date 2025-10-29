package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.annotations.CascadeType;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.e2eq.ontology.annotations.RelationType;
import dev.morphia.annotations.Entity;

import java.util.ArrayList;
import java.util.List;

@Entity(value = "it_parents")
@OntologyClass(id = "Parent")
public class ItParent extends UnversionedBaseModel {

    @OntologyProperty(
        id = "children",
        edgeType = "HAS_CHILD",
        ref = "Child",
        relation = RelationType.ONE_TO_MANY,
        cascade = { CascadeType.ORPHAN_REMOVE }
    )
    private List<ItChild> children = new ArrayList<>();

    public List<ItChild> getChildren() { return children; }
    public void setChildren(List<ItChild> children) { this.children = children; }

    @Override
    public String bmFunctionalArea() { return "it"; }

    @Override
    public String bmFunctionalDomain() { return "parents"; }
}

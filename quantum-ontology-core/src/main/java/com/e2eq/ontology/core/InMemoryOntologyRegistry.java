
package com.e2eq.ontology.core;

import java.util.*;
import com.e2eq.ontology.core.OntologyRegistry.*;

public final class InMemoryOntologyRegistry implements OntologyRegistry {
    private final Map<String, ClassDef> classes;
    private final Map<String, PropertyDef> properties;
    private final List<PropertyChainDef> chains;

    public InMemoryOntologyRegistry(TBox tbox) {
        this.classes = tbox.classes();
        this.properties = tbox.properties();
        this.chains = tbox.propertyChains();
    }
    public Optional<ClassDef> classOf(String name){ return Optional.ofNullable(classes.get(name)); }
    public Optional<PropertyDef> propertyOf(String name){ return Optional.ofNullable(properties.get(name)); }
    public List<PropertyChainDef> propertyChains(){ return chains; }
    public Map<String, PropertyDef> properties(){ return properties; }
}

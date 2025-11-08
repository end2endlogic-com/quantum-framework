package com.e2eq.ontology.runtime;

import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Vetoed;

import java.util.*;

@Vetoed // Avoid CDI ambiguity with existing OntologyRegistry producers in core and tests
@ApplicationScoped
public class OntologyRegistryProducer {

    @Produces
    @ApplicationScoped
    public OntologyRegistry produceRegistry() {
        // Minimal in-memory TBox so the REST resource works out-of-the-box.
        Map<String, ClassDef> classes = new HashMap<>();
        classes.put("Agent", new ClassDef("Agent", Set.of(), Set.of(), Set.of()));
        classes.put("Person", new ClassDef("Person", Set.of("Agent"), Set.of(), Set.of()));

        Map<String, PropertyDef> props = new HashMap<>();
        props.put("knows", new PropertyDef(
                "knows",
                Optional.of("Person"),
                Optional.of("Person"),
                false,
                Optional.empty(),
                false,
                true,
                false,
                Set.of()
        ));

        TBox tbox = new TBox(classes, props, List.of());
        return OntologyRegistry.inMemory(tbox);
    }
}

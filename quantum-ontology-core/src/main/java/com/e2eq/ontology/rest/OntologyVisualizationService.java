package com.e2eq.ontology.rest;

import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.rest.dto.OntologyVisualizationPayload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class OntologyVisualizationService {

    private final OntologyRegistry.TBox tbox;

    @Inject
    public OntologyVisualizationService(OntologyRegistry.TBox tbox) {
        this.tbox = Objects.requireNonNull(tbox, "tbox");
    }

    public OntologyVisualizationPayload buildVisualization() {
        Map<String, OntologyRegistry.ClassDef> classDefs = tbox.classes() != null ? tbox.classes() : Map.of();
        Map<String, OntologyRegistry.PropertyDef> propertyDefs = tbox.properties() != null ? tbox.properties() : Map.of();
        List<OntologyRegistry.PropertyChainDef> propertyChains = tbox.propertyChains() != null ? tbox.propertyChains() : List.of();

        List<OntologyVisualizationPayload.Node> nodes = classDefs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new OntologyVisualizationPayload.Node(
                        entry.getKey(),
                        "class",
                        toSortedList(entry.getValue().parents()),
                        toSortedList(entry.getValue().disjointWith()),
                        toSortedList(entry.getValue().sameAs())
                ))
                .collect(Collectors.toList());

        Map<String, List<List<String>>> chainsByProperty = new HashMap<>();
        List<OntologyVisualizationPayload.Rule> rules = new ArrayList<>();
        for (OntologyRegistry.PropertyChainDef chain : propertyChains) {
            List<String> steps = chain.chain() != null ? List.copyOf(chain.chain()) : List.of();
            rules.add(new OntologyVisualizationPayload.Rule(steps, chain.implies()));
            chainsByProperty.computeIfAbsent(chain.implies(), key -> new ArrayList<>()).add(steps);
        }

        List<OntologyVisualizationPayload.Link> links = propertyDefs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    OntologyRegistry.PropertyDef def = entry.getValue();
                    List<List<String>> chains = chainsByProperty.getOrDefault(entry.getKey(), List.of());
                    String domain = def.domain().orElse(null);
                    String range = def.range().orElse(null);
                    return new OntologyVisualizationPayload.Link(
                            entry.getKey(),
                            domain,
                            range,
                            chains.isEmpty() ? "property" : "derived",
                            domain,
                            range,
                            def.inverse(),
                            def.inverseOf().orElse(null),
                            def.transitive(),
                            chains
                    );
                })
                .collect(Collectors.toList());

        return new OntologyVisualizationPayload(List.copyOf(nodes), List.copyOf(links), List.copyOf(rules));
    }

    private static List<String> toSortedList(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toUnmodifiableList());
    }
}

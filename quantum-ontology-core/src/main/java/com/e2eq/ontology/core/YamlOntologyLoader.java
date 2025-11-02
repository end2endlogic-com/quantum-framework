package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.e2eq.ontology.annotations.RelationType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads an OntologyRegistry.TBox from a YAML file or classpath resource.
 * The YAML format mirrors a simple structure with classes, properties and chains.
 */
public final class YamlOntologyLoader {

    // DTOs mirroring YAML
    public record YOntology(Integer version, List<YClass> classes, List<YProperty> properties, List<YChain> chains) {}
    public record YClass(String id) {}
    public record YProperty(
            String id,
            String domain,
            String range,
            // Backwards compatible: either 'functional' or 'relation' can be used; 'relation' takes precedence
            Boolean functional,
            String relation,
            String inverseOf,
            Boolean transitive,
            Boolean symmetric,
            List<String> subPropertyOf
    ) {}
    public record YChain(List<String> chain, String implies) {}

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public TBox loadFromClasspath(String resourcePath) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Resource not found: " + resourcePath);
            return toTBox(mapper.readValue(in, YOntology.class));
        }
    }

    public TBox loadFromPath(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return toTBox(mapper.readValue(in, YOntology.class));
        }
    }

    public TBox load(InputStream in) throws IOException {
        return toTBox(mapper.readValue(in, YOntology.class));
    }

    private TBox toTBox(YOntology y) {
        List<YClass> yClasses = Optional.ofNullable(y.classes()).orElse(List.of());
        Map<String, ClassDef> classes = yClasses.stream()
                .collect(Collectors.toMap(YClass::id, c -> new ClassDef(c.id(), new LinkedHashSet<>(), Set.of(), Set.of()), (a,b)->a, LinkedHashMap::new));

        Map<String, PropertyDef> props = new LinkedHashMap<>();
        for (YProperty p : Optional.ofNullable(y.properties()).orElse(List.of())) {
            Set<String> supers = new LinkedHashSet<>(Optional.ofNullable(p.subPropertyOf()).orElse(List.of()));

            // Determine functional based on relation if provided; fallback to explicit functional flag
            boolean functional;
            if (p.relation() != null && !p.relation().isBlank()) {
                RelationType rt;
                try {
                    rt = RelationType.valueOf(p.relation().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException iae) {
                    throw new RuntimeException("Unknown relation type '" + p.relation() + "' for property '" + p.id() + "'. Expected one of: " + Arrays.toString(RelationType.values()));
                }
                functional = (rt == RelationType.ONE_TO_ONE || rt == RelationType.MANY_TO_ONE);
            } else {
                functional = Boolean.TRUE.equals(p.functional());
            }

            PropertyDef def = new PropertyDef(
                    p.id(),
                    Optional.ofNullable(p.domain()),
                    Optional.ofNullable(p.range()),
                    p.inverseOf() != null && !p.inverseOf().isBlank(),
                    Optional.ofNullable(p.inverseOf()).filter(s -> !s.isBlank()),
                    Boolean.TRUE.equals(p.transitive()),
                    Boolean.TRUE.equals(p.symmetric()),
                    functional,
                    supers
            );
            props.put(p.id(), def);
        }

        List<PropertyChainDef> chains = Optional.ofNullable(y.chains()).orElse(List.of()).stream()
                .map(ch -> new PropertyChainDef(ch.chain(), ch.implies()))
                .collect(Collectors.toList());

        TBox tbox = new TBox(classes, props, chains);
        OntologyValidator.validate(tbox);
        return tbox;
    }
}

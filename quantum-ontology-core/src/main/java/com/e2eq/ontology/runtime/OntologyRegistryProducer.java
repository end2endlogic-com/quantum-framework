package com.e2eq.ontology.runtime;

import com.e2eq.ontology.core.OntologyRegistry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the ontology TBox definition from the classpath and exposes both the raw
 * {@link OntologyRegistry.TBox} and an {@link OntologyRegistry} backed by it for
 * injection across the application. Placed in the ontology module so that
 * applications only gain the CDI beans when they depend on the module.
 */
@ApplicationScoped
public class OntologyRegistryProducer {

    private final ObjectMapper objectMapper;
    private final String tboxLocation;

    private OntologyRegistry.TBox tbox;
    private OntologyRegistry registry;

    @Inject
    public OntologyRegistryProducer(ObjectMapper objectMapper,
                                    @ConfigProperty(name = "quantum.ontology.tbox", defaultValue = "ontology/tbox.json")
                                    String tboxLocation) {
        this.objectMapper = objectMapper;
        this.tboxLocation = tboxLocation;
    }

    @PostConstruct
    void init() {
        this.tbox = loadTBox();
        this.registry = OntologyRegistry.inMemory(tbox);
    }

    @Produces
    public OntologyRegistry.TBox tbox() {
        return tbox;
    }

    @Produces
    public OntologyRegistry registry() {
        return registry;
    }

    private OntologyRegistry.TBox loadTBox() {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(tboxLocation)) {
            if (stream == null) {
                throw new IllegalStateException("Unable to locate ontology TBox resource at " + tboxLocation);
            }
            RawTBox raw = objectMapper.readValue(stream, RawTBox.class);
            return toTBox(raw);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load ontology TBox from " + tboxLocation, e);
        }
    }

    private OntologyRegistry.TBox toTBox(RawTBox raw) {
        Map<String, OntologyRegistry.ClassDef> classes = new HashMap<>();
        if (raw.classes != null) {
            raw.classes.forEach((name, def) -> {
                Set<String> parents = toSet(def != null ? def.parents : null);
                Set<String> disjointWith = toSet(def != null ? def.disjointWith : null);
                Set<String> sameAs = toSet(def != null ? def.sameAs : null);
                classes.put(name, new OntologyRegistry.ClassDef(name, parents, disjointWith, sameAs));
            });
        }

        Map<String, OntologyRegistry.PropertyDef> properties = new HashMap<>();
        if (raw.properties != null) {
            raw.properties.forEach((name, def) -> {
                if (def == null) {
                    def = new RawPropertyDef();
                }
                Optional<String> domain = optional(def.domain);
                Optional<String> range = optional(def.range);
                Optional<String> inverseOf = optional(def.inverseOf);
                boolean inverse = Boolean.TRUE.equals(def.inverse);
                boolean transitive = Boolean.TRUE.equals(def.transitive);
                properties.put(name, new OntologyRegistry.PropertyDef(name, domain, range, inverse, inverseOf, transitive));
            });
        }

        List<OntologyRegistry.PropertyChainDef> chains = new ArrayList<>();
        if (raw.propertyChains != null) {
            for (RawPropertyChainDef def : raw.propertyChains) {
                if (def == null || def.implies == null || def.implies.isBlank()) {
                    continue;
                }
                List<String> chain = def.chain != null
                        ? def.chain.stream().filter(Objects::nonNull).toList()
                        : List.of();
                chains.add(new OntologyRegistry.PropertyChainDef(chain, def.implies));
            }
        }

        return new OntologyRegistry.TBox(Map.copyOf(classes), Map.copyOf(properties), List.copyOf(chains));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Set<String> toSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new HashSet<>(values));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawTBox {
        Map<String, RawClassDef> classes;
        Map<String, RawPropertyDef> properties;
        List<RawPropertyChainDef> propertyChains;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawClassDef {
        List<String> parents;
        List<String> disjointWith;
        List<String> sameAs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawPropertyDef {
        String domain;
        String range;
        Boolean inverse;
        String inverseOf;
        Boolean transitive;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RawPropertyChainDef {
        List<String> chain;
        String implies;
    }
}

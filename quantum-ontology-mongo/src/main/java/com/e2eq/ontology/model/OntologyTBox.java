package com.e2eq.ontology.model;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.ontology.core.OntologyRegistry.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import dev.morphia.annotations.Field;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity(value = "ontology_tbox")
@Indexes({
    @Index(options = @IndexOptions(name = "idx_tbox_hash"), fields = { @Field("tboxHash") }),
    @Index(options = @IndexOptions(name = "idx_tbox_yaml_hash"), fields = { @Field("yamlHash") }),
    @Index(options = @IndexOptions(name = "idx_tbox_applied"), fields = { @Field("appliedAt") })
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyTBox extends UnversionedBaseModel {
    
    private String tboxHash;           // SHA-256 hash of TBox content
    private String yamlHash;            // Hash of source YAML (for correlation)
    private Date appliedAt;              // When this TBox was applied
    private String source;              // Source description (YAML path, etc.)
    
    // Serialized TBox content - stored as embedded documents
    private Map<String, ClassDefData> classes;
    private Map<String, PropertyDefData> properties;
    private List<PropertyChainDefData> chains;
    
    // Metadata
    private Integer version;            // Optional version number
    private String description;         // Optional description
    
    public OntologyTBox(TBox tbox, String tboxHash, String yamlHash, String source) {
        this.tboxHash = tboxHash;
        this.yamlHash = yamlHash;
        this.source = source;
        this.appliedAt = new Date();
        this.classes = convertClasses(tbox.classes());
        this.properties = convertProperties(tbox.properties());
        this.chains = convertChains(tbox.propertyChains());
    }
    
    public TBox toTBox() {
        Map<String, ClassDef> classDefs = classes != null ? 
            classes.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    e -> new ClassDef(e.getValue().name, e.getValue().parents, 
                                     e.getValue().disjointWith, e.getValue().sameAs)
                )) : Map.of();
        
        Map<String, PropertyDef> propDefs = properties != null ?
            properties.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    e -> new PropertyDef(
                        e.getValue().name,
                        e.getValue().domain != null ? Optional.of(e.getValue().domain) : Optional.empty(),
                        e.getValue().range != null ? Optional.of(e.getValue().range) : Optional.empty(),
                        e.getValue().inverse,
                        e.getValue().inverseOf != null ? Optional.of(e.getValue().inverseOf) : Optional.empty(),
                        e.getValue().transitive,
                        e.getValue().symmetric,
                        e.getValue().functional,
                        e.getValue().subPropertyOf != null ? e.getValue().subPropertyOf : Set.of()
                    )
                )) : Map.of();
        
        List<PropertyChainDef> chainDefs = chains != null ?
            chains.stream()
                .map(c -> new PropertyChainDef(c.chain, c.implies))
                .toList() : List.of();
        
        return new TBox(classDefs, propDefs, chainDefs);
    }
    
    private Map<String, ClassDefData> convertClasses(Map<String, ClassDef> classes) {
        if (classes == null) return Map.of();
        return classes.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> new ClassDefData(e.getValue())
            ));
    }
    
    private Map<String, PropertyDefData> convertProperties(Map<String, PropertyDef> properties) {
        if (properties == null) return Map.of();
        return properties.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> new PropertyDefData(e.getValue())
            ));
    }
    
    private List<PropertyChainDefData> convertChains(List<PropertyChainDef> chains) {
        if (chains == null) return List.of();
        return chains.stream()
            .map(PropertyChainDefData::new)
            .toList();
    }
    
    @Override
    public String bmFunctionalArea() {
        return "ontology";
    }
    
    @Override
    public String bmFunctionalDomain() {
        return "tbox";
    }
    
    // DTO classes for Morphia serialization
    @Data
    @NoArgsConstructor
    public static class ClassDefData {
        private String name;
        private Set<String> parents;
        private Set<String> disjointWith;
        private Set<String> sameAs;
        
        public ClassDefData(ClassDef def) {
            this.name = def.name();
            this.parents = def.parents();
            this.disjointWith = def.disjointWith();
            this.sameAs = def.sameAs();
        }
    }
    
    @Data
    @NoArgsConstructor
    public static class PropertyDefData {
        private String name;
        private String domain;
        private String range;
        private boolean inverse;
        private String inverseOf;
        private boolean transitive;
        private boolean symmetric;
        private boolean functional;
        private Set<String> subPropertyOf;
        
        public PropertyDefData(PropertyDef def) {
            this.name = def.name();
            this.domain = def.domain().orElse(null);
            this.range = def.range().orElse(null);
            this.inverse = def.inverse();
            this.inverseOf = def.inverseOf().orElse(null);
            this.transitive = def.transitive();
            this.symmetric = def.symmetric();
            this.functional = def.functional();
            this.subPropertyOf = def.subPropertyOf();
        }
    }
    
    @Data
    @NoArgsConstructor
    public static class PropertyChainDefData {
        private List<String> chain;
        private String implies;
        
        public PropertyChainDefData(PropertyChainDef def) {
            this.chain = def.chain();
            this.implies = def.implies();
        }
    }
}


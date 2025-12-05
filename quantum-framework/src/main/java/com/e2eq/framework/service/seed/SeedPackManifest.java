package com.e2eq.framework.service.seed;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Represents the declarative manifest that describes a seed pack.
 * Enhanced with Bean Validation annotations for better validation.
 */
@Data
@EqualsAndHashCode
@ToString
@RegisterForReflection
public final class SeedPackManifest {

    private static final String DEFAULT_MANIFEST = "manifest.yaml";

    @NotBlank(message = "seedPack name is required")
    private String seedPack;

    @NotBlank(message = "version is required")
    private String version;

    @Valid
    private List<Dataset> datasets;
    private List<String> includes;

    @Valid
    private List<Archetype> archetypes;
    // Optional scope block to control applicability
    private SeedScope scope;
    private transient String sourceDescription;


    public List<Dataset> getDatasets() {
        return datasets == null ? List.of() : Collections.unmodifiableList(datasets);
    }

    public List<SeedPackRef> getIncludeRefs() {
        if (includes == null || includes.isEmpty()) {
            return List.of();
        }
        return includes.stream().map(SeedPackRef::parse).collect(Collectors.toUnmodifiableList());
    }

    public List<Archetype> getArchetypes() {
        return archetypes == null ? List.of() : Collections.unmodifiableList(archetypes);
    }

    public Optional<Archetype> findArchetype(String name) {
        if (archetypes == null) {
            return Optional.empty();
        }
        return archetypes.stream()
                .filter(a -> Objects.equals(name, a.getName()))
                .findFirst();
    }

    public String getSourceDescription() {
        return sourceDescription == null ? DEFAULT_MANIFEST : sourceDescription;
    }

    void setSourceDescription(String sourceDescription) {
        this.sourceDescription = sourceDescription;
    }

    /**
     * Validates the manifest using both programmatic checks and Bean Validation.
     *
     * @throws IllegalStateException if validation fails
     */
    public void validate() {
        // Bean Validation checks (basic constraints)
        jakarta.validation.Validator validator = jakarta.validation.Validation
                .buildDefaultValidatorFactory()
                .getValidator();

        java.util.Set<jakarta.validation.ConstraintViolation<SeedPackManifest>> violations = validator.validate(this);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("Validation failed for manifest " + getSourceDescription() + ": ");
            violations.forEach(v -> sb.append(v.getPropertyPath()).append(" ").append(v.getMessage()).append("; "));
            throw new IllegalStateException(sb.toString());
        }

        // Additional programmatic validation
        if (datasets != null) {
            datasets.forEach(dataset -> dataset.validate(getSourceDescription()));
        }
        if (archetypes != null) {
            archetypes.forEach(archetype -> archetype.validate(getSourceDescription()));
        }
    }

    public static SeedPackManifest load(InputStream in, String sourceDescription) {
        Objects.requireNonNull(in, "in");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Constructor constructor = new Constructor(SeedPackManifest.class, options);
        Yaml yaml = new Yaml(constructor);
        try {
            SeedPackManifest manifest = yaml.load(in);
            if (manifest == null) {
                throw new IllegalStateException("Empty manifest at " + sourceDescription);
            }
            manifest.setSourceDescription(sourceDescription);
            manifest.validate();
            return manifest;
        } catch (YAMLException ex) {
            throw new IllegalStateException("Unable to parse seed manifest " + sourceDescription + ": " + ex.getMessage(), ex);
        }
    }


    @Data
    @EqualsAndHashCode
    public static final class Dataset {
        private String collection;
        private String file;
        private List<String> naturalKey;
        private Boolean upsert;
        private List<Index> requiredIndexes;
        private List<Transform> transforms;
        // Optional: fully qualified class name of the UnversionedBaseModel to persist via Morphia
        private String modelClass;
        // Optional: fully qualified class name of the Morphia repository bean to use directly
        private String repoClass;


        public List<String> getNaturalKey() {
            return naturalKey == null ? List.of() : Collections.unmodifiableList(naturalKey);
        }

        public Boolean getUpsert() {
            return upsert;
        }

        public boolean isUpsert() {
            // Default behavior: insert-only (skip existing). Only upsert when explicitly enabled.
            return upsert != null && upsert;
        }

        public void setUpsert(boolean upsert) {
            this.upsert = upsert;
        }

        public List<Index> getRequiredIndexes() {
            return requiredIndexes == null ? List.of() : Collections.unmodifiableList(requiredIndexes);
        }

        public List<Transform> getTransforms() {
            return transforms == null ? List.of() : Collections.unmodifiableList(transforms);
        }

        void validate(String source) {
            // collection may be omitted when modelClass or repoClass is specified; otherwise it's required
            boolean hasCollection = collection != null && !collection.isBlank();
            boolean hasModel = modelClass != null && !modelClass.isBlank();
            boolean hasRepo = repoClass != null && !repoClass.isBlank();
            if (!hasCollection && !hasModel && !hasRepo) {
                throw new IllegalStateException("Dataset collection is required in manifest " + source + " when neither modelClass nor repoClass is specified");
            }
            String datasetId = hasCollection ? collection : (hasModel ? modelClass : (hasRepo ? repoClass : "<unknown>"));
            if (file == null || file.isBlank()) {
                throw new IllegalStateException("Dataset file is required for " + datasetId + " in manifest " + source);
            }
            if (naturalKey == null || naturalKey.isEmpty()) {
                throw new IllegalStateException("Dataset naturalKey is required for " + datasetId + " in manifest " + source);
            }
            if (requiredIndexes != null) {
                requiredIndexes.forEach(index -> index.validate(datasetId, source));
            }
            if (transforms != null) {
                transforms.forEach(transform -> transform.validate(datasetId, source));
            }
        }
    }

    public static final class Index {
        @Setter
        private Map<String, Integer> keys;
        @Setter
        private Boolean unique;
        @Getter
        @Setter
        private String name;

        public Map<String, Integer> getKeys() {
            return keys == null ? Map.of() : Collections.unmodifiableMap(keys);
        }

        public Boolean getUnique() {
            return unique;
        }

        public boolean isUnique() {
            return unique != null && unique;
        }

        public void setUnique(boolean unique) {
            this.unique = unique;
        }

       void validate(String collection, String source) {
            if (keys == null || keys.isEmpty()) {
                throw new IllegalStateException("Index keys are required for collection " + collection + " in manifest " + source);
            }
        }
    }

    public static final class Transform {
        @Setter
        private String type;
        @Setter
        private Map<String, Object> config;

        public String getType() {
            return type;
        }

        public Map<String, Object> getConfig() {
            return config == null ? Map.of() : Collections.unmodifiableMap(config);
        }

        void validate(String dataset, String source) {
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("Transform type is required for dataset " + dataset + " in manifest " + source);
            }
        }
    }

    @Data
    @EqualsAndHashCode
    @ToString
    public static final class Archetype {
        private String name;
        private List<String> includes;

        public List<SeedPackRef> getIncludeRefs() {
            if (includes == null || includes.isEmpty()) {
                return List.of();
            }
            return includes.stream().map(SeedPackRef::parse).collect(Collectors.toUnmodifiableList());
        }

        void validate(String source) {
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Archetype name is required in manifest " + source);
            }
        }
    }
}

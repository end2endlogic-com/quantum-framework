package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;

import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OntologyRegistry implementation that loads from persisted TBox.
 * Provides the same functionality as YamlBackedOntologyRegistry but
 * is backed by a TBox loaded from database persistence.
 */
public final class PersistedOntologyRegistry implements OntologyRegistry {
    private final Map<String, ClassDef> classes;
    private final Map<String, PropertyDef> properties;
    private final List<PropertyChainDef> chains;
    private final String tboxHash;
    private final String yamlHash;
    private final boolean needsReindex;

    // observable version
    private final AtomicLong version = new AtomicLong(1L);
    private final SubmissionPublisher<Long> publisher = new SubmissionPublisher<>();

    public PersistedOntologyRegistry(TBox tbox, String tboxHash, String yamlHash, boolean needsReindex) {
        this.classes = tbox.classes();
        this.properties = tbox.properties();
        this.chains = tbox.propertyChains();
        this.tboxHash = tboxHash;
        this.yamlHash = yamlHash;
        this.needsReindex = needsReindex;
        // Emit initial version value for late subscribers
        this.publisher.submit(this.version.get());
    }

    @Override
    public Optional<ClassDef> classOf(String name) {
        return Optional.ofNullable(classes.get(name));
    }

    @Override
    public Optional<PropertyDef> propertyOf(String name) {
        return Optional.ofNullable(properties.get(name));
    }

    @Override
    public List<PropertyChainDef> propertyChains() {
        return chains;
    }

    @Override
    public Map<String, PropertyDef> properties() {
        return properties;
    }

    @Override
    public Map<String, ClassDef> classes() {
        return classes;
    }

    @Override
    public TBox getCurrentTBox() {
        return new TBox(classes, properties, chains);
    }

    @Override
    public String getHash() {
        return yamlHash != null && !yamlHash.equals("unknown") ? yamlHash : tboxHash;
    }

    @Override
    public Flow.Publisher<Long> versionPublisher() {
        return publisher;
    }

    @Override
    public boolean needsReindex() {
        return needsReindex;
    }

    /**
     * Get the TBox hash (distinct from YAML hash)
     */
    public String getTboxHash() {
        return tboxHash;
    }

    // Optional helper to advance the version and notify subscribers
    public long bumpVersion() {
        long v = version.incrementAndGet();
        publisher.submit(v);
        return v;
    }
}


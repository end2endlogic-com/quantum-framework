package com.e2eq.ontology.core;

import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Closes dangling <em>class</em> references in a TBox so a module can declare object properties
 * whose domain/range is a class OWNED BY ANOTHER MODULE (a federated, cross-module reference)
 * without the whole TBox being rejected.
 *
 * <p>In a federated ontology each service publishes only the classes it owns, yet its properties
 * may legitimately point at classes another service declares — e.g.
 * {@code ApplicationPackVersion.usesOntology -> Ontology}, where {@code Ontology} is owned by a
 * different pack. {@link OntologyValidator} enforces LOCAL referential integrity and treats such a
 * dangling domain/range as fatal. That is correct for a hand-authored, self-contained TBox (and for
 * direct admission, which stays strict), but wrong for a <em>source build</em> assembled from
 * annotations/Morphia/YAML: there a single external edge would otherwise abort the merge and discard
 * every real class the module does own.</p>
 *
 * <p>This step materializes each referenced-but-undeclared domain/range class as an EMPTY stub
 * (tagged {@code external=true} in metadata for local provenance). Stubs carry no structure, so when
 * the OWNING module later federates its real definition, {@link OntologyMerger}'s union semantics fold
 * the two together with no conflict. Property-to-property references (inverseOf, subPropertyOf, chain
 * members) are intentionally NOT closed here — those are local integrity constraints that must still
 * fail loudly rather than be papered over.</p>
 */
public final class OntologyReferenceCloser {

    /** Metadata key marking a class that was materialized only to satisfy an external reference. */
    public static final String EXTERNAL_METADATA_KEY = "external";

    private OntologyReferenceCloser() {}

    /**
     * Return a TBox identical to {@code tbox} except that every class referenced by a property's
     * domain or range — but not declared in {@code tbox} — is added as an empty external stub. If
     * no references are dangling, {@code tbox} is returned unchanged.
     */
    public static TBox closeClassReferences(TBox tbox) {
        if (tbox == null) {
            return null;
        }
        Map<String, ClassDef> classes = new LinkedHashMap<>(tbox.classes());
        int before = classes.size();
        for (PropertyDef p : tbox.properties().values()) {
            addStubIfMissing(classes, p.domain().orElse(null));
            addStubIfMissing(classes, p.range().orElse(null));
        }
        if (classes.size() == before) {
            return tbox;
        }
        return new TBox(classes, tbox.properties(), tbox.propertyChains());
    }

    private static void addStubIfMissing(Map<String, ClassDef> classes, String classId) {
        if (classId == null || classId.isBlank() || classes.containsKey(classId)) {
            return;
        }
        classes.put(classId, new ClassDef(classId, Set.of(), Set.of(), Set.of(),
                classId, Set.of(), Map.of(EXTERNAL_METADATA_KEY, Boolean.TRUE)));
    }
}

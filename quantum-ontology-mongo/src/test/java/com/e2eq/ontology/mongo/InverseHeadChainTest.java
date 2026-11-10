package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.ontology.core.EdgeChanges;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Inverse edges are owned by the node whose explicit edge produced them: {@code link sourceDocument doc}
 * writes {@code doc documentHasLink link} at the document. Re-materializing the document must keep that
 * edge, and a chain that starts with it ({@code [documentHasLink, linkTarget] => documentSupports})
 * must derive at the document when the link changes.
 */
@QuarkusTest
public class InverseHeadChainTest {

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    OntologyMaterializer materializer;

    @Inject
    ChainPropagator propagator;

    @Inject
    OntologyEdgeRepo edgeRepo;

    private DataDomain tenant;
    private SecurityCallScope.Scope ignoredRulesScope;

    @BeforeEach
    void setup() {
        ignoredRulesScope = SecurityCallScope.openIgnoringRules();
        tboxRepo.deleteAll();
        edgeRepo.deleteAll();
        provider.clearCache();
        tenant = new DataDomain("org1", "acc1", "inverse-head-tenant", 0, "user1");
        TenantOntologyTBox doc = new TenantOntologyTBox(evidenceTBox(), "inv-hash", "inv-yaml", "test", "1.0.0");
        doc.setDataDomain(tenant);
        doc.setRefName("tbox-inverse-head");
        doc.setDisplayName("Inverse head chains");
        tboxRepo.save(doc);
    }

    @AfterEach
    void cleanup() {
        provider.clearCache();
        if (ignoredRulesScope != null) {
            ignoredRulesScope.close();
        }
    }

    @Test
    void reSavingTheDocumentKeepsTheInverseEdgeItDoesNotOwn() {
        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkSourceDocument", "doc1", "Document"));
        assertEquals(Set.of("link1"), dsts("doc1", "documentHasLink"));

        write("doc1", "Document");

        assertEquals(Set.of("link1"), dsts("doc1", "documentHasLink"));
    }

    @Test
    void aChainStartingWithAnInverseDerivesAtTheDocument() {
        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkSourceDocument", "doc1", "Document"),
                edge("link1", "EvidenceLink", "linkTarget", "decl1", "Declaration"));

        assertEquals(Set.of("decl1"), dsts("doc1", "documentSupports"));
    }

    @Test
    void retargetingTheLinkUpdatesTheDocument() {
        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkSourceDocument", "doc1", "Document"),
                edge("link1", "EvidenceLink", "linkTarget", "decl1", "Declaration"));

        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkSourceDocument", "doc1", "Document"),
                edge("link1", "EvidenceLink", "linkTarget", "decl2", "Declaration"));

        assertEquals(Set.of("decl2"), dsts("doc1", "documentSupports"));
    }

    @Test
    void detachingTheLinkPrunesTheDocumentsEdges() {
        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkSourceDocument", "doc1", "Document"),
                edge("link1", "EvidenceLink", "linkTarget", "decl1", "Declaration"));

        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkTarget", "decl1", "Declaration"));

        assertEquals(Set.of(), dsts("doc1", "documentHasLink"));
        assertEquals(Set.of(), dsts("doc1", "documentSupports"));
    }

    @Test
    void reSavingTheDocumentKeepsItsChainDerivation() {
        write("link1", "EvidenceLink", edge("link1", "EvidenceLink", "linkSourceDocument", "doc1", "Document"),
                edge("link1", "EvidenceLink", "linkTarget", "decl1", "Declaration"));

        write("doc1", "Document");

        assertEquals(Set.of("decl1"), dsts("doc1", "documentSupports"));
    }

    private void write(String id, String type, Reasoner.Edge... edges) {
        EdgeChanges changes = materializer.apply(tenant, id, type, List.of(edges));
        propagator.propagate(null, tenant, id, changes);
    }

    private Set<String> dsts(String src, String p) {
        return edgeRepo.findBySrc((String) null, tenant, src).stream()
                .filter(e -> p.equals(e.getP()))
                .map(e -> e.getDst())
                .collect(Collectors.toSet());
    }

    private static Reasoner.Edge edge(String src, String srcType, String p, String dst, String dstType) {
        return new Reasoner.Edge(src, srcType, p, dst, dstType, false, Optional.empty());
    }

    static TBox evidenceTBox() {
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        for (String c : List.of("Document", "EvidenceLink", "Declaration")) {
            classes.put(c, new ClassDef(c, Set.of(), Set.of(), Set.of()));
        }
        Map<String, PropertyDef> props = new LinkedHashMap<>();
        props.put("linkSourceDocument", new PropertyDef("linkSourceDocument", Optional.of("EvidenceLink"),
                Optional.of("Document"), false, Optional.of("documentHasLink"), false, false, true, Set.of(), false));
        props.put("documentHasLink", new PropertyDef("documentHasLink", Optional.of("Document"),
                Optional.of("EvidenceLink"), false, Optional.of("linkSourceDocument"), false, false, false, Set.of(), true));
        props.put("linkTarget", new PropertyDef("linkTarget", Optional.of("EvidenceLink"),
                Optional.of("Declaration"), false, Optional.empty(), false, false, true, Set.of(), false));
        props.put("documentSupports", new PropertyDef("documentSupports", Optional.of("Document"),
                Optional.of("Declaration"), false, Optional.empty(), false, false, false, Set.of(), true));
        return new TBox(classes, props,
                List.of(new PropertyChainDef(List.of("documentHasLink", "linkTarget"), "documentSupports")));
    }
}

package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.EdgeAttestation;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.query.filters.Filter;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(TestDatabaseCleanupResource.class)
public class EdgeMetadataFilterTest {

    @Inject
    OntologyEdgeRepo edgeRepo;

    private DataDomain testDataDomain;
    private SecurityCallScope.Scope privilegedRepoScope;

    @BeforeEach
    void setup() {
        privilegedRepoScope = SecurityCallScope.openIgnoringRules();
        edgeRepo.deleteAll("test-realm");

        testDataDomain = new DataDomain();
        testDataDomain.setOrgRefName("test-org");
        testDataDomain.setAccountNum("1111111111");
        testDataDomain.setTenantId("test-realm");
        testDataDomain.setOwnerId("system");
        testDataDomain.setDataSegment(0);
    }

    @AfterEach
    void tearDown() {
        if (privilegedRepoScope != null) {
            privilegedRepoScope.close();
            privilegedRepoScope = null;
        }
        SecurityContext.clear();
    }

    @Test
    @DisplayName("Bulk upsert persists edge metadata and supports retrieval")
    void testBulkUpsertAndMetadataPersistence() {
        Date from = new Date(System.currentTimeMillis() - 86400000L); // yesterday
        Date to = new Date(System.currentTimeMillis() + 86400000L);   // tomorrow
        EdgeAttestation attestation = new EdgeAttestation("key-1", "sig-xyz-123", "Ed25519", new Date());

        DataDomainInfo ddi = new DataDomainInfo("test-org", "1111111111", "test-realm", 0);
        EdgeRecord record = new EdgeRecord();
        record.setDataDomainInfo(ddi);
        record.setSrc("UserA");
        record.setSrcType("User");
        record.setP("memberOf");
        record.setDst("Org1");
        record.setDstType("Organization");
        record.setValidFrom(from);
        record.setValidTo(to);
        record.setSecurityLabel("CONFIDENTIAL");
        record.setCompartments(List.of("FINANCE", "HR"));
        record.setConfidence(0.95);
        record.setAssertionMethod("MANUAL");
        record.setAllowedPurposes(List.of("FRAUD_DETECTION", "AUDIT"));
        record.setConsentId("CONSENT-001");
        record.setAttestation(attestation);
        record.setMutuallyExclusiveWith(List.of("auditorOf"));

        edgeRepo.bulkUpsertEdgeRecords("test-realm", List.of(record));

        List<OntologyEdge> loaded = edgeRepo.findBySrcAndP("test-realm", testDataDomain, "UserA", "memberOf");
        assertFalse(loaded.isEmpty(), "Edge should be persisted and found");
        OntologyEdge edge = loaded.get(0);
        assertEquals("CONFIDENTIAL", edge.getSecurityLabel());
        assertEquals(0.95, edge.getConfidence());
        assertEquals("MANUAL", edge.getAssertionMethod());
        assertEquals("CONSENT-001", edge.getConsentId());
        assertEquals(List.of("FINANCE", "HR"), edge.getCompartments());
        assertEquals(List.of("FRAUD_DETECTION", "AUDIT"), edge.getAllowedPurposes());
        assertEquals(List.of("auditorOf"), edge.getMutuallyExclusiveWith());
        assertNotNull(edge.getAttestation());
        assertEquals("key-1", edge.getAttestation().getKeyId());
        assertEquals("sig-xyz-123", edge.getAttestation().getSignature());
    }

    @Test
    @DisplayName("Morphia convertToFilter compiles queries with @asOf and @minConfidence on OntologyEdge")
    void testMacroFilterCompilationOnOntologyEdge() {
        PrincipalContext pc = new PrincipalContext.Builder()
                .withUserId("admin")
                .withDefaultRealm("test-realm")
                .withDataDomain(testDataDomain)
                .build();
        ResourceContext rc = new ResourceContext.Builder()
                .withRealm("test-realm")
                .withArea("ontology")
                .withFunctionalDomain("edges")
                .withAction("read")
                .build();

        MorphiaUtils.VariableBundle bundle = MorphiaUtils.buildVariableBundle(pc, rc, null, OntologyEdge.class, null);

        String query = "@asOf(2026-09-20T00:00:00Z) && @minConfidence(0.8) && securityLabel:\"CONFIDENTIAL\"";
        Filter filter = MorphiaUtils.convertToFilter(query, bundle, OntologyEdge.class);
        assertNotNull(filter, "Filter should compile successfully on OntologyEdge model");
    }
}

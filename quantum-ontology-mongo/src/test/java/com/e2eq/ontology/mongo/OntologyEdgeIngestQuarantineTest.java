package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainComponentBinding;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.EdgeRecord;
import com.e2eq.ontology.model.OntologyEdge;
import com.e2eq.ontology.model.QuarantinedEdge;
import com.e2eq.ontology.repo.EdgeIngestResult;
import com.e2eq.ontology.repo.EdgeIngestSummary;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import com.e2eq.ontology.repo.SourcePolicyContext;
import dev.morphia.Datastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N3 acceptance (S3): the governed, fail-closed edge-ingest path with quarantine.
 *
 * <p>Asserts that {@link OntologyEdgeRepo#ingestEdges} resolves each row through the DataDomain
 * resolver and either stamps the resolved domain into the {@code edges} collection or, when the
 * row cannot be placed, writes a {@link QuarantinedEdge} to {@code ontology_edge_quarantine} and
 * NOTHING to {@code edges} — never synthesizing a default/{@code __unowned} domain.</p>
 */
@QuarkusTest
public class OntologyEdgeIngestQuarantineTest {

    private static final String REALM = "ingest-quarantine-test";

    @Inject
    OntologyEdgeRepo edgeRepo;

    private DataDomain principalDD;

    @AfterEach
    void clearSecurityContext() {
        SecurityContext.clear();
    }

    @BeforeEach
    void setup() {
        // A principal/security context so ingestEdges' upsert and ds() resolve to a known realm.
        principalDD = new DataDomain();
        principalDD.setOrgRefName("ingest-org");
        principalDD.setAccountNum("9999999999");
        principalDD.setTenantId("ingest-tenant");
        principalDD.setOwnerId("system");
        principalDD.setDataSegment(0);

        DomainContext domainContext = DomainContext.builder()
                .tenantId("ingest-tenant")
                .defaultRealm(REALM)
                .orgRefName("ingest-org")
                .accountId("9999999999")
                .dataSegment(0)
                .build();

        PrincipalContext principal = new PrincipalContext.Builder()
                .withUserId("system@ingest")
                .withDefaultRealm(REALM)
                .withDomainContext(domainContext)
                .withDataDomain(principalDD)
                .withRoles(new String[]{"admin"})
                .withScope("AUTHENTICATED")
                .build();
        SecurityContext.setPrincipalContext(principal);
        SecurityContext.setResourceContext(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT);

        // Clean both collections in this realm.
        edgeRepo.deleteAll(REALM);
        Datastore ds = edgeRepo.ds(REALM);
        ds.getCollection(OntologyEdge.class).deleteMany(new org.bson.Document());
        ds.getCollection(QuarantinedEdge.class).deleteMany(new org.bson.Document());
    }

    /**
     * A FROM_SOURCE policy that requires tenant_id (+org/account literals) from the row. Rows that
     * carry tenant_id resolve; rows that don't are unplaceable → quarantine.
     */
    private SourcePolicyContext sourceCtx() {
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("ingest-org"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("9999999999"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);

        DataDomainPolicy policy = new DataDomainPolicy();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("ontology:edge", entry);
        policy.setPolicyEntries(entries);

        return new SourcePolicyContext("feed-1", policy);
    }

    private EdgeRecord row(String src, String dst, Map<String, Object> prov) {
        // DataDomainInfo is deliberately set to something the resolver does NOT use; placement is
        // governed by the policy, not by the caller-supplied domain.
        DataDomainInfo bogus = new DataDomainInfo("BOGUS-ORG", "0000000000", "bogus-tenant", 0);
        EdgeRecord e = new EdgeRecord(bogus, "Order", src, "placedBy", "Customer", dst, false, prov, new java.util.Date());
        return e;
    }

    private long edgeCount() {
        return edgeRepo.ds(REALM).getCollection(OntologyEdge.class).countDocuments();
    }

    private long quarantineCount() {
        return edgeRepo.ds(REALM).getCollection(QuarantinedEdge.class).countDocuments();
    }

    @Test
    void unplaceableRow_quarantined_zeroEdges_oneQuarantine_noSynthesizedDomain() {
        // No tenant_id in prov → resolver returns Unresolvable.
        EdgeRecord bad = row("ORDER-BAD", "CUST-BAD", new HashMap<>());

        EdgeIngestSummary summary = edgeRepo.ingestEdges(sourceCtx(), List.of(bad));

        assertEquals(0, edgeCount(), "no edge document may be written for an unplaceable row");
        assertEquals(1, quarantineCount(), "exactly one quarantine document");
        assertEquals(0, summary.getAcceptedCount());
        assertEquals(1, summary.getQuarantinedCount());

        // The quarantined edge carries the failing reason and source, and there is NO synthesized
        // / __unowned domain anywhere in the edges collection (it is empty).
        QuarantinedEdge q = edgeRepo.ds(REALM).find(QuarantinedEdge.class).first();
        assertNotNull(q);
        assertEquals("ORDER-BAD", q.getSrc());
        assertEquals("feed-1", q.getSourceId());
        assertNotNull(q.getReason());
        assertTrue(q.getReason().toLowerCase().contains("tenant"), "reason should name the missing component");

        // No edge has a synthesized/__unowned domain.
        List<OntologyEdge> all = edgeRepo.ds(REALM).find(OntologyEdge.class).iterator().toList();
        assertTrue(all.isEmpty(), "edges collection must be unchanged (empty)");
        for (OntologyEdge e : all) {
            assertFalse("__unowned".equals(e.getDataDomain().getTenantId()));
            assertFalse("__UNRESOLVABLE__".equals(e.getDataDomain().getOrgRefName()));
        }
    }

    @Test
    void resolvableRow_oneScopedEdge_noQuarantine() {
        Map<String, Object> prov = new HashMap<>();
        prov.put("tenant_id", "ingest-tenant");
        EdgeRecord good = row("ORDER-OK", "CUST-OK", prov);

        EdgeIngestSummary summary = edgeRepo.ingestEdges(sourceCtx(), List.of(good));

        assertEquals(1, edgeCount(), "exactly one edge document for a resolvable row");
        assertEquals(0, quarantineCount(), "no quarantine for a resolvable row");
        assertEquals(1, summary.getAcceptedCount());
        assertEquals(0, summary.getQuarantinedCount());

        OntologyEdge e = edgeRepo.ds(REALM).find(OntologyEdge.class).first();
        assertNotNull(e);
        assertEquals("ingest-org", e.getDataDomain().getOrgRefName());
        assertEquals("9999999999", e.getDataDomain().getAccountNum());
        assertEquals("ingest-tenant", e.getDataDomain().getTenantId());
        assertEquals("ORDER-OK", e.getSrc());
        assertEquals("CUST-OK", e.getDst());
    }

    @Test
    void mixedBatch_oneResolvable_oneNot_oneEdge_oneQuarantine_resultsEnumerateBoth() {
        Map<String, Object> goodProv = new HashMap<>();
        goodProv.put("tenant_id", "ingest-tenant");
        EdgeRecord good = row("ORDER-OK", "CUST-OK", goodProv);
        EdgeRecord bad = row("ORDER-BAD", "CUST-BAD", new HashMap<>());

        List<EdgeRecord> batch = new ArrayList<>();
        batch.add(good);
        batch.add(bad);

        EdgeIngestSummary summary = edgeRepo.ingestEdges(sourceCtx(), batch);

        assertEquals(1, edgeCount(), "exactly 1 edge persisted (the resolvable one)");
        assertEquals(1, quarantineCount(), "exactly 1 quarantine (the unplaceable one)");
        assertEquals(1, summary.getAcceptedCount());
        assertEquals(1, summary.getQuarantinedCount());
        assertEquals(2, summary.getResults().size(), "per-record results enumerate both rows");

        boolean sawAccepted = false, sawQuarantined = false;
        for (EdgeIngestResult r : summary.getResults()) {
            if (r.isAccepted()) sawAccepted = true;
            if (r.isQuarantined()) sawQuarantined = true;
        }
        assertTrue(sawAccepted && sawQuarantined, "results enumerate both an ACCEPTED and a QUARANTINED row");

        // The persisted edge is the good one with the governed domain.
        OntologyEdge e = edgeRepo.ds(REALM).find(OntologyEdge.class).first();
        assertEquals("ORDER-OK", e.getSrc());
        assertEquals("ingest-tenant", e.getDataDomain().getTenantId());
    }
}

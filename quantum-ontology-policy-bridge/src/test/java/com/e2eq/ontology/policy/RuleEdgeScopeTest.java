package com.e2eq.ontology.policy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import dev.morphia.query.filters.Filter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** quantum.ontology.rule-edge-scope: how far a rule's edge lookups reach. */
public class RuleEdgeScopeTest {

    /** Records the DataDomain each lookup is made in. */
    static class RecordingRepo extends OntologyEdgeRepo {
        final List<DataDomain> asked = new ArrayList<>();

        @Override
        public Set<String> srcIdsByDst(DataDomain dataDomain, String p, String dst, Filter... edgeFilters) {
            asked.add(dataDomain);
            return Set.of("a1b2c3d4e5f6a7b8c9d0e1f2");
        }

        @Override
        public Set<String> srcIdsByDst(DataDomain dataDomain, String p, String dst) {
            return srcIdsByDst(dataDomain, p, dst, new Filter[0]);
        }
    }

    private static DataDomain caller() {
        DataDomain dd = new DataDomain();
        dd.setOrgRefName("org");
        dd.setAccountNum("0000000001");
        dd.setTenantId("tenant-a");
        dd.setDataSegment(0);
        dd.setOwnerId("u");
        return dd;
    }

    @Test
    void byDefaultALookupStaysInTheCallersDataDomain() {
        RecordingRepo repo = new RecordingRepo();
        new ListQueryRewriter(repo).hasEdge(caller(), "issuedBy", "party-1");
        assertEquals("tenant-a", repo.asked.get(0).getTenantId());
    }

    @Test
    void accountScopeReachesEveryTenantOfTheCallersOrganisationAndAccount() {
        RecordingRepo repo = new RecordingRepo();
        ListQueryRewriter rewriter = new ListQueryRewriter(repo);
        rewriter.setRuleEdgeScope(com.e2eq.framework.model.persistent.base.RuleEdgeScope.ACCOUNT);
        rewriter.hasEdge(caller(), "issuedBy", "party-1");
        DataDomain asked = repo.asked.get(0);
        assertEquals(OntologyEdgeRepo.ANY_TENANT, asked.getTenantId());
        assertEquals("org", asked.getOrgRefName());
        assertEquals("0000000001", asked.getAccountNum());
        assertEquals(0, asked.getDataSegment());
    }

    @Test
    void anyTenantDropsOnlyTheTenantCondition() {
        DataDomain any = caller();
        any.setTenantId(OntologyEdgeRepo.ANY_TENANT);
        String filters = Arrays.toString(new OntologyEdgeRepo().dataDomainFilters(any));
        assertFalse(filters.contains("dataDomain.tenantId"), filters);
        assertTrue(filters.contains("dataDomain.orgRefName") && filters.contains("dataDomain.accountNum")
                && filters.contains("dataDomain.dataSegment"), filters);
        assertTrue(Arrays.toString(new OntologyEdgeRepo().dataDomainFilters(caller())).contains("dataDomain.tenantId"));
    }

    @Test
    void anyTenantIsNeverWrittenOrDeleted() {
        DataDomain any = caller();
        any.setTenantId(OntologyEdgeRepo.ANY_TENANT);
        OntologyEdgeRepo repo = new OntologyEdgeRepo();
        assertThrows(IllegalArgumentException.class, () -> repo.deleteBySrc("realm", any, "src", false));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteBySrcAndPredicate("realm", any, "src", "p"));
        assertThrows(IllegalArgumentException.class, () -> repo.deleteDerivedByDataDomain("realm", any));
    }
}

package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.service.OntologyMetaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Multi-principal correctness guard for the B2 read-path unification blocker.
 *
 * <p>The realm read lookup ({@code findActiveTBox(realmDataDomain(realm))}) and the
 * admission write ({@code TBoxAdmissionService.dataDomainFor}) BOTH key on
 * {@link TenantOntologyRegistryProvider#realmDataDomain(String)}. If that derivation
 * mixed in the ambient {@link PrincipalContext} DataDomain (the original bug), two
 * callers in the SAME realm but under DIFFERENT principals (e.g. the
 * {@code OntologyReindexer} system principal vs. an HTTP admin under their JWT) would
 * compute DIFFERENT keys, and an activation written under one principal would be
 * invisible to a read under the other.</p>
 *
 * <p>These tests assert {@code realmDataDomain(realm)} is a PURE function of the
 * realm string — byte-identical under two different ambient principals — and that a
 * tenant TBox activated under {@code realmDataDomain(REALM)} resolves on reads
 * performed under either principal. They FAIL against the old ambient-mixing
 * derivation and PASS after Change 1.</p>
 */
public class TenantOntologyRegistryProviderMultiPrincipalTest {

    private static final String REALM = "acme-realm";

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    private PrincipalContext principalWith(String org, String account, String owner, int segment) {
        DataDomain dd = new DataDomain(org, account, REALM, segment, owner);
        return new PrincipalContext.Builder()
                .withDefaultRealm(REALM)
                .withDataDomain(dd)
                .withUserId("user@" + org)
                .withScope("ALL")
                .build();
    }

    /**
     * Core blocker assertion: realmDataDomain(realm) is principal-INDEPENDENT.
     * <p>Compute it under principal A (org "ontology", account "0000000000"), then
     * under a totally different principal B (org "acmeOrg", account "9999999999",
     * different segment), same realm. The two results MUST be equal. Under the old
     * ambient-mixing code the org/account/owner/segment were overridden from the
     * principal, so the two keys diverged and this fails.</p>
     */
    @Test
    void realmDataDomainIsPrincipalIndependent() {
        SecurityContext.setPrincipalContext(principalWith("ontology", "0000000000", "system", 0));
        DataDomain underA = TenantOntologyRegistryProvider.realmDataDomain(REALM);
        SecurityContext.clearPrincipalContext();

        SecurityContext.setPrincipalContext(principalWith("acmeOrg", "9999999999", "adminUser", 7));
        DataDomain underB = TenantOntologyRegistryProvider.realmDataDomain(REALM);
        SecurityContext.clearPrincipalContext();

        // Same realm -> byte-identical key regardless of ambient principal.
        assertEquals(underA, underB,
                "realmDataDomain(realm) must be a pure function of realm, not the ambient principal");
        // And with NO principal at all.
        DataDomain underNone = TenantOntologyRegistryProvider.realmDataDomain(REALM);
        assertEquals(underA, underNone,
                "realmDataDomain(realm) must be identical with no ambient principal");

        // Spell out the canonical fixed-per-realm convention so a regression is obvious.
        assertEquals(REALM, underA.getTenantId());
        assertEquals(REALM, underA.getOrgRefName());
        assertEquals(REALM, underA.getAccountNum());
        assertEquals(REALM, underA.getOwnerId());
        assertEquals(0, underA.getDataSegment());
    }

    /**
     * End-to-end read resolution under two different principals: an active tenant
     * TBox is written under {@code realmDataDomain(REALM)}; two reads simulated under
     * DIFFERENT ambient principals (different org/account, same realm) BOTH resolve
     * the SAME activated TBox because the lookup key is principal-independent.
     */
    @Test
    void twoPrincipalsResolveSameActivatedTBox() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
        OntologyMetaService metaService = mock(OntologyMetaService.class);

        // The single key under which the active tenant TBox is stored == realmDataDomain(REALM).
        DataDomain activeKey = TenantOntologyRegistryProvider.realmDataDomain(REALM);

        TBox tenantTBox = new TBox(
                Map.of("TenantOnlyClass", new ClassDef("TenantOnlyClass", Set.of(), Set.of(), Set.of())),
                Map.of(),
                List.of());
        TenantOntologyTBox doc = new TenantOntologyTBox(tenantTBox, "tenant-hash", "tenant-yaml", "submit", "1.0.0");
        doc.setActive(true);

        // The repo only returns the doc when queried with the SAME key it was stored under.
        // (Captures the realm-deterministic-key contract: a divergent key -> empty.)
        when(tenantRepo.findActiveTBox(any(DataDomain.class))).thenAnswer(inv -> {
            DataDomain queried = inv.getArgument(0);
            return queried.equals(activeKey) ? Optional.of(doc) : Optional.empty();
        });

        // Legacy fallback would surface a different class; if a read drifts onto a
        // different key it would fall through to here and we'd detect it.
        TBox legacyTBox = new TBox(
                Map.of("LegacyOnlyClass", new ClassDef("LegacyOnlyClass", Set.of(), Set.of(), Set.of())),
                Map.of(),
                List.of());
        OntologyTBox legacyDoc = new OntologyTBox(legacyTBox, "legacy-hash", "legacy-yaml", "ontology.yaml");
        lenient().when(legacyRepo.findLatest()).thenReturn(Optional.of(legacyDoc));

        AtomicReference<OntologyRegistry> underA = new AtomicReference<>();
        AtomicReference<OntologyRegistry> underB = new AtomicReference<>();

        // Read 1 under principal A. Fresh provider (no cache) per read so the cache
        // cannot mask a per-principal key divergence.
        SecurityContext.setPrincipalContext(principalWith("ontology", "0000000000", "system", 0));
        underA.set(newProvider(tenantRepo, legacyRepo, metaService).getRegistryForRealm(REALM));
        SecurityContext.clearPrincipalContext();

        // Read 2 under principal B (different org/account, same realm).
        SecurityContext.setPrincipalContext(principalWith("acmeOrg", "9999999999", "adminUser", 7));
        underB.set(newProvider(tenantRepo, legacyRepo, metaService).getRegistryForRealm(REALM));
        SecurityContext.clearPrincipalContext();

        assertTrue(underA.get().classOf("TenantOnlyClass").isPresent(),
                "read under principal A must resolve the activated tenant TBox");
        assertTrue(underB.get().classOf("TenantOnlyClass").isPresent(),
                "read under principal B (different principal, same realm) must resolve the SAME activated tenant TBox");
        assertFalse(underA.get().classOf("LegacyOnlyClass").isPresent());
        assertFalse(underB.get().classOf("LegacyOnlyClass").isPresent());
    }

    private TenantOntologyRegistryProvider newProvider(TenantOntologyTBoxRepo tenantRepo,
                                                       OntologyTBoxRepo legacyRepo,
                                                       OntologyMetaService metaService) {
        TenantOntologyRegistryProvider p = new TenantOntologyRegistryProvider();
        p.tenantTboxRepo = tenantRepo;
        p.tboxRepo = legacyRepo;
        p.metaService = metaService;
        return p;
    }
}

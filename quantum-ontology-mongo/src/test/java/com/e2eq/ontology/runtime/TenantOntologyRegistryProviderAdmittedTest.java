package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.service.OntologyMetaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * B5 SLICE 1 — provider resolution of the realm's admitted-predicate set
 * (no Quarkus / no Mongo / no SecurityContext, so the realm resolves to
 * {@code defaultRealm}, which we set directly on the package-private field).
 */
public class TenantOntologyRegistryProviderAdmittedTest {

    private static final String REALM = "acme-realm";

    private TenantOntologyRegistryProvider newProvider(TenantOntologyTBoxRepo tenantRepo) {
        TenantOntologyRegistryProvider p = new TenantOntologyRegistryProvider();
        p.tenantTboxRepo = tenantRepo;
        p.tboxRepo = mock(OntologyTBoxRepo.class);
        p.metaService = mock(OntologyMetaService.class);
        p.defaultRealm = REALM; // no SecurityContext in this test -> getCurrentRealm() == defaultRealm
        return p;
    }

    private static TenantOntologyTBox docWithAdmitted(Set<String> admitted) {
        TBox tbox = new TBox(
                Map.of("Associate", new ClassDef("Associate", Set.of(), Set.of(), Set.of())),
                Map.of(), List.of());
        TenantOntologyTBox doc = new TenantOntologyTBox(tbox, "h", "y", "submit", "1.0.0");
        doc.setActive(true);
        doc.setAdmittedPredicates(admitted);
        return doc;
    }

    @Test
    void returnsAdmittedSetWhenActiveDocHasNonNullField() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(docWithAdmitted(Set.of("canSeeLocation"))));

        Optional<Set<String>> admitted = newProvider(tenantRepo).admittedPredicatesForCurrentRealm();

        assertTrue(admitted.isPresent());
        assertEquals(Set.of("canSeeLocation"), admitted.get());
        verify(tenantRepo).findActiveTBox(eq(TenantOntologyRegistryProvider.realmDataDomain(REALM)));
    }

    @Test
    void emptyWhenActiveDocHasNullAdmittedField_legacy() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(docWithAdmitted(null)));

        assertTrue(newProvider(tenantRepo).admittedPredicatesForCurrentRealm().isEmpty(),
                "null admittedPredicates field == legacy (all admitted) == Optional.empty()");
    }

    @Test
    void emptyWhenNoActiveDoc() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class))).thenReturn(Optional.empty());

        assertTrue(newProvider(tenantRepo).admittedPredicatesForCurrentRealm().isEmpty());
    }

    @Test
    void emptyAndNullSafeWhenRepoThrows() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenThrow(new RuntimeException("mongo down"));

        assertTrue(newProvider(tenantRepo).admittedPredicatesForCurrentRealm().isEmpty(),
                "must be null-safe and treat resolution failure as legacy (all admitted)");
    }

    // --- B5 3-state disposition (admittedVocabularyForCurrentRealm) -----------

    @Test
    void governed_whenActiveDocHasNonNullAdmittedSet() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(docWithAdmitted(Set.of("canSeeLocation"))));

        AdmittedVocabularyResult r = newProvider(tenantRepo).admittedVocabularyForCurrentRealm();

        assertEquals(AdmittedVocabularyResult.Kind.GOVERNED, r.kind());
        assertEquals(Set.of("canSeeLocation"), r.admitted());
    }

    @Test
    void legacy_whenActiveDocHasNullAdmittedField() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(docWithAdmitted(null)));

        assertEquals(AdmittedVocabularyResult.Kind.LEGACY,
                newProvider(tenantRepo).admittedVocabularyForCurrentRealm().kind());
    }

    @Test
    void legacy_whenNoActiveDoc() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class))).thenReturn(Optional.empty());

        assertEquals(AdmittedVocabularyResult.Kind.LEGACY,
                newProvider(tenantRepo).admittedVocabularyForCurrentRealm().kind());
    }

    @Test
    void unavailable_whenRepoThrows_notLegacy() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenThrow(new RuntimeException("mongo down"));

        AdmittedVocabularyResult r = newProvider(tenantRepo).admittedVocabularyForCurrentRealm();

        assertEquals(AdmittedVocabularyResult.Kind.UNAVAILABLE, r.kind(),
                "a read failure must surface as UNAVAILABLE (fail-closed), not LEGACY (fail-open)");
    }

    @Test
    void legacyOptionalProjection_mapsUnavailableToEmpty_backCompat() {
        // The deprecated 2-state method must preserve its exact prior behavior:
        // read failure -> Optional.empty() (so pre-existing callers/tests stay green).
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenThrow(new RuntimeException("mongo down"));

        assertTrue(newProvider(tenantRepo).admittedPredicatesForCurrentRealm().isEmpty());
    }

    @Test
    void readsFreshOnEachCall_notCached() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(docWithAdmitted(Set.of("a"))))
                .thenReturn(Optional.of(docWithAdmitted(Set.of("a", "b"))));

        TenantOntologyRegistryProvider provider = newProvider(tenantRepo);
        assertEquals(Set.of("a"), provider.admittedPredicatesForCurrentRealm().get());
        assertEquals(Set.of("a", "b"), provider.admittedPredicatesForCurrentRealm().get());
        verify(tenantRepo, times(2)).findActiveTBox(any(DataDomain.class));
    }
}

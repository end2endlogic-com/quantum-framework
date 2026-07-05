package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry;
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
 * Cluster cache-coherence for {@link TenantOntologyRegistryProvider} (provider level,
 * no Quarkus / no Mongo — repos mocked).
 *
 * <p>Reproduces the clustered-staleness defect and its fix: when a TBox is activated on
 * a PEER instance, that instance's in-process cache is invalidated but ours is not. The
 * provider must still observe the change on the next read because it probes the durable
 * store's active-hash (throttled by {@code quantum.ontology.cache.staleness-check-interval-ms}).</p>
 */
public class TenantOntologyRegistryProviderClusterCoherenceTest {

    private static final String REALM = "acme-realm";

    private TenantOntologyRegistryProvider newProvider(TenantOntologyTBoxRepo tenantRepo,
                                                       long stalenessCheckIntervalMs) {
        TenantOntologyRegistryProvider p = new TenantOntologyRegistryProvider();
        // Fields are package-private @Inject; same-package test sets them directly.
        p.tenantTboxRepo = tenantRepo;
        p.tboxRepo = mock(OntologyTBoxRepo.class);
        p.metaService = mock(OntologyMetaService.class);
        p.stalenessCheckIntervalMs = stalenessCheckIntervalMs;
        return p;
    }

    private TenantOntologyTBox activeDoc(String className, String hash) {
        TBox tbox = new TBox(
                Map.of(className, new ClassDef(className, Set.of(), Set.of(), Set.of())),
                Map.of(),
                List.of());
        TenantOntologyTBox doc = new TenantOntologyTBox(tbox, hash, "yaml", "submit", "1.0.0");
        doc.setActive(true);
        return doc;
    }

    @Test
    void observesPeerActivationWhenCoherenceCheckEnabled() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        DataDomain dd = TenantOntologyRegistryProvider.realmDataDomain(REALM);

        // V1 is the active TBox both this instance and the durable store agree on.
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(activeDoc("V1Class", "hash-v1")));
        when(tenantRepo.findActiveTBoxHash(eq(dd))).thenReturn(Optional.of("hash-v1"));

        // interval 0 => probe the durable store on every read (strongest coherence).
        TenantOntologyRegistryProvider provider = newProvider(tenantRepo, 0L);

        OntologyRegistry first = provider.getRegistryForRealm(REALM);
        assertTrue(first.classOf("V1Class").isPresent(), "initial read should reflect V1");

        // A PEER instance activates V2: the durable store now holds V2, but THIS instance's
        // in-process cache was never invalidated (invalidateRealm was called on the peer only).
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(activeDoc("V2Class", "hash-v2")));
        when(tenantRepo.findActiveTBoxHash(eq(dd))).thenReturn(Optional.of("hash-v2"));

        OntologyRegistry second = provider.getRegistryForRealm(REALM);
        assertTrue(second.classOf("V2Class").isPresent(),
                "coherence probe must observe the peer's activation and rebuild from V2");
        assertFalse(second.classOf("V1Class").isPresent(), "stale V1 must no longer be served");
    }

    @Test
    void servesStaleCacheWhenCoherenceCheckDisabled() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        DataDomain dd = TenantOntologyRegistryProvider.realmDataDomain(REALM);

        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(activeDoc("V1Class", "hash-v1")));
        when(tenantRepo.findActiveTBoxHash(eq(dd))).thenReturn(Optional.of("hash-v1"));

        // Negative interval => never probe (legacy in-process-only invalidation).
        TenantOntologyRegistryProvider provider = newProvider(tenantRepo, -1L);

        assertTrue(provider.getRegistryForRealm(REALM).classOf("V1Class").isPresent());

        // Peer activates V2 in the durable store; this instance is not invalidated.
        when(tenantRepo.findActiveTBox(any(DataDomain.class)))
                .thenReturn(Optional.of(activeDoc("V2Class", "hash-v2")));
        when(tenantRepo.findActiveTBoxHash(eq(dd))).thenReturn(Optional.of("hash-v2"));

        OntologyRegistry second = provider.getRegistryForRealm(REALM);
        assertTrue(second.classOf("V1Class").isPresent(),
                "with the coherence check disabled, the stale cached V1 is still served");
        assertFalse(second.classOf("V2Class").isPresent());
        // The durable-store probe must not even be consulted when disabled.
        verify(tenantRepo, never()).findActiveTBoxHash(any(DataDomain.class));
    }
}

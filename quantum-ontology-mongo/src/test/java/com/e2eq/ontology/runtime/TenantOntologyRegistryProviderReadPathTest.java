package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.service.OntologyMetaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * B2 read-path unification (provider level, no Quarkus / no Mongo).
 *
 * <p>Drives {@link TenantOntologyRegistryProvider#getRegistryForRealm(String)}
 * (which delegates to the package-private {@code loadOrBuildRegistryForRealm})
 * with both repos mocked, asserting:</p>
 * <ol>
 *   <li>when an ACTIVE {@link TenantOntologyTBox} exists for
 *       {@code realmDataDomain(realm)}, the registry is built from it; and</li>
 *   <li>when none exists, the load falls back UNCHANGED to the legacy
 *       {@code tboxRepo.findLatest()} path.</li>
 * </ol>
 */
public class TenantOntologyRegistryProviderReadPathTest {

    private static final String REALM = "acme-realm";

    private TenantOntologyRegistryProvider newProvider(TenantOntologyTBoxRepo tenantRepo,
                                                       OntologyTBoxRepo legacyRepo,
                                                       OntologyMetaService metaService) {
        TenantOntologyRegistryProvider p = new TenantOntologyRegistryProvider();
        // Fields are package-private @Inject; same-package test sets them directly.
        p.tenantTboxRepo = tenantRepo;
        p.tboxRepo = legacyRepo;
        p.metaService = metaService;
        return p;
    }

    @Test
    void prefersActiveTenantTBoxWhenPresent() {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
        OntologyMetaService metaService = mock(OntologyMetaService.class);

        // Active tenant TBox carries a class unique to it: "TenantOnlyClass".
        TBox tenantTBox = new TBox(
                Map.of("TenantOnlyClass", new ClassDef("TenantOnlyClass", Set.of(), Set.of(), Set.of())),
                Map.of(),
                List.of());
        TenantOntologyTBox doc = new TenantOntologyTBox(tenantTBox, "tenant-hash", "tenant-yaml", "submit", "1.0.0");
        doc.setActive(true);

        when(tenantRepo.findActiveTBox(any(DataDomain.class))).thenReturn(Optional.of(doc));

        TenantOntologyRegistryProvider provider = newProvider(tenantRepo, legacyRepo, metaService);
        OntologyRegistry registry = provider.getRegistryForRealm(REALM);

        assertNotNull(registry);
        assertTrue(registry.classOf("TenantOnlyClass").isPresent(),
                "registry must be built from the ACTIVE TenantOntologyTBox");

        // The lookup key is the canonical realmDataDomain(realm).
        verify(tenantRepo).findActiveTBox(eq(TenantOntologyRegistryProvider.realmDataDomain(REALM)));
        // Legacy path must NOT be consulted when an active tenant TBox exists.
        verify(legacyRepo, never()).findLatest();
    }

    @Test
    void fallsBackToLegacyFindLatestWhenNoActiveTenantTBox() {
        // This module's test application.properties sets force-rebuild=true (which
        // bypasses the legacy persisted load). For THIS test we want to exercise the
        // legacy findLatest() persisted path, so override config to persist=true /
        // force-rebuild=false and drop the cached MicroProfile config so the
        // provider rebuilds it.
        System.setProperty("quantum.ontology.tbox.persist", "true");
        System.setProperty("quantum.ontology.tbox.force-rebuild", "false");
        org.eclipse.microprofile.config.spi.ConfigProviderResolver resolver =
                org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance();
        try {
            resolver.releaseConfig(resolver.getConfig());
        } catch (Throwable ignored) {
        }

        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
        OntologyMetaService metaService = mock(OntologyMetaService.class);

        // No active tenant TBox for this realm.
        when(tenantRepo.findActiveTBox(any(DataDomain.class))).thenReturn(Optional.empty());

        // Legacy store has a TBox with a class unique to it: "LegacyOnlyClass".
        TBox legacyTBox = new TBox(
                Map.of("LegacyOnlyClass", new ClassDef("LegacyOnlyClass", Set.of(), Set.of(), Set.of())),
                Map.of(),
                List.of());
        OntologyTBox legacyDoc = new OntologyTBox(legacyTBox, "legacy-hash", "legacy-yaml", "ontology.yaml");
        when(legacyRepo.findLatest()).thenReturn(Optional.of(legacyDoc));

        // Make the legacy yaml-hash gate pass: observed currentHash == persisted yamlHash.
        when(metaService.observeYaml(ArgumentMatchers.<Optional<java.nio.file.Path>>any(), anyString()))
                .thenReturn(new OntologyMetaService.Result(null, "legacy-yaml"));
        when(metaService.getMeta()).thenReturn(Optional.empty());

        try {
            TenantOntologyRegistryProvider provider = newProvider(tenantRepo, legacyRepo, metaService);
            OntologyRegistry registry = provider.getRegistryForRealm(REALM);

            assertNotNull(registry);
            assertTrue(registry.classOf("LegacyOnlyClass").isPresent(),
                    "registry must fall back to the legacy findLatest() TBox when no active tenant TBox exists");
            assertFalse(registry.classOf("TenantOnlyClass").isPresent());

            verify(tenantRepo).findActiveTBox(eq(TenantOntologyRegistryProvider.realmDataDomain(REALM)));
            verify(legacyRepo).findLatest();
        } finally {
            // Restore module test defaults so other tests in this JVM are unaffected.
            System.clearProperty("quantum.ontology.tbox.persist");
            System.clearProperty("quantum.ontology.tbox.force-rebuild");
            try {
                resolver.releaseConfig(resolver.getConfig());
            } catch (Throwable ignored) {
            }
        }
    }
}

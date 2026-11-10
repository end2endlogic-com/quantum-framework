package com.e2eq.ontology.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.e2eq.ontology.core.RelationStability;
import com.e2eq.ontology.core.SourceTBoxGate;
import com.e2eq.ontology.core.StableRelationDiff;
import com.e2eq.ontology.core.OntologyRegistry.TBox;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.service.OntologyMetaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 *   <li>when none exists, the registry is built from sources and checked against the last
 *       built TBox of the same builder ({@code tboxRepo.findLatestForBuilder}) for the stable-relation
 *       contract (P3c).</li>
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
        verify(legacyRepo, never()).findLatestForBuilder(anyString());
    }

    /** Runs {@code body} with persistence on and {@code stableDiff} set, restoring module defaults. */
    private static void withConfig(String stableDiff, Runnable body) {
        // This module's test application.properties sets persist=false; these tests need the
        // persisted baseline. Drop the cached MicroProfile config so the provider re-reads it.
        System.setProperty("quantum.ontology.tbox.persist", "true");
        if (stableDiff != null) System.setProperty("quantum.ontology.stable-diff", stableDiff);
        org.eclipse.microprofile.config.spi.ConfigProviderResolver resolver =
                org.eclipse.microprofile.config.spi.ConfigProviderResolver.instance();
        try {
            resolver.releaseConfig(resolver.getConfig());
        } catch (Throwable ignored) {
        }
        try {
            body.run();
        } finally {
            System.clearProperty("quantum.ontology.tbox.persist");
            System.clearProperty("quantum.ontology.stable-diff");
            try {
                resolver.releaseConfig(resolver.getConfig());
            } catch (Throwable ignored) {
            }
        }
    }

    /** A provider with no active tenant TBox whose last built TBox is {@code baseline}. */
    private TenantOntologyRegistryProvider sourceProvider(OntologyTBoxRepo legacyRepo, OntologyTBox baseline) {
        TenantOntologyTBoxRepo tenantRepo = mock(TenantOntologyTBoxRepo.class);
        OntologyMetaService metaService = mock(OntologyMetaService.class);
        when(tenantRepo.findActiveTBox(any(DataDomain.class))).thenReturn(Optional.empty());
        when(legacyRepo.findLatestForBuilder(anyString())).thenReturn(Optional.ofNullable(baseline));
        when(legacyRepo.findByHashAndBuilder(anyString(), anyString())).thenReturn(Optional.empty());
        when(metaService.observeYaml(ArgumentMatchers.<Optional<java.nio.file.Path>>any(), anyString()))
                .thenReturn(new OntologyMetaService.Result(null, "yaml-hash"));
        when(metaService.getMeta()).thenReturn(Optional.empty());
        return newProvider(tenantRepo, legacyRepo, metaService);
    }

    /** The last built TBox, carrying one stable relation the module's sources do not declare. */
    private static OntologyTBox baselineWithStableRelation(Integer version) {
        TBox tbox = new TBox(
                Map.of("Order", new ClassDef("Order", Set.of(), Set.of(), Set.of()),
                        "Customer", new ClassDef("Customer", Set.of(), Set.of(), Set.of())),
                Map.of("orderContract", new PropertyDef("orderContract", Optional.of("Order"), Optional.of("Customer"),
                        false, Optional.empty(), false, false, false, Set.of(), false, null, Set.of(), Map.of(),
                        RelationStability.STABLE)),
                List.of());
        OntologyTBox doc = new OntologyTBox(tbox, "baseline-hash", "yaml-hash", "ontology.yaml");
        doc.setVersion(version);
        return doc;
    }

    @Test
    void buildsFromSourcesInsteadOfAStalePersistedTBox() {
        // The persisted TBox used to be served whenever the YAML hash matched, so annotation and
        // entity changes were ignored until a forced rebuild. It is now only the contract baseline.
        TBox legacyTBox = new TBox(
                Map.of("LegacyOnlyClass", new ClassDef("LegacyOnlyClass", Set.of(), Set.of(), Set.of())),
                Map.of(),
                List.of());
        OntologyTBox legacyDoc = new OntologyTBox(legacyTBox, "legacy-hash", "yaml-hash", "ontology.yaml");
        withConfig(null, () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            OntologyRegistry registry = sourceProvider(legacyRepo, legacyDoc).getRegistryForRealm(REALM);

            assertFalse(registry.classOf("LegacyOnlyClass").isPresent());
            assertTrue(registry.classOf("Order").isPresent(), "built from the module's ontology.yaml");
            verify(legacyRepo).findLatestForBuilder("default");
            ArgumentCaptor<OntologyTBox> saved = ArgumentCaptor.forClass(OntologyTBox.class);
            verify(legacyRepo).save(saved.capture());
            assertEquals(1, saved.getValue().getVersion(), "the pack version is recorded with the build");
        });
    }

    @Test
    void refusesASourceTBoxThatRemovesAStableRelation() {
        withConfig(null, () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            TenantOntologyRegistryProvider provider = sourceProvider(legacyRepo, baselineWithStableRelation(1));

            SourceTBoxGate.StableContractException e = assertThrows(SourceTBoxGate.StableContractException.class,
                    () -> provider.getRegistryForRealm(REALM));
            assertEquals(List.of("orderContract"),
                    e.violations().stream().map(StableRelationDiff.Violation::relation).toList());
            assertEquals(StableRelationDiff.Code.STABLE_RELATION_REMOVED, e.violations().get(0).code());
            verify(legacyRepo, never()).save(any(OntologyTBox.class));
            assertFalse(provider.hasCachedRegistry(REALM), "a refused build is not cached");
        });
    }

    @Test
    void aPackVersionBumpPublishesTheRemoval() {
        withConfig(null, () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            // The module's ontology.yaml declares version 1
            OntologyRegistry registry = sourceProvider(legacyRepo, baselineWithStableRelation(0))
                    .getRegistryForRealm(REALM);
            assertFalse(registry.propertyOf("orderContract").isPresent());
            verify(legacyRepo).save(any(OntologyTBox.class));
        });
    }

    @Test
    void warnModeLogsTheBreachAndBuilds() {
        withConfig("warn", () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            OntologyRegistry registry = sourceProvider(legacyRepo, baselineWithStableRelation(1))
                    .getRegistryForRealm(REALM);
            assertTrue(registry.classOf("Order").isPresent());
            verify(legacyRepo).save(any(OntologyTBox.class));
        });
    }

    @Test
    void anUnreadableBaselineRefusesTheBuild() {
        withConfig(null, () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            TenantOntologyRegistryProvider provider = sourceProvider(legacyRepo, null);
            when(legacyRepo.findLatestForBuilder(anyString())).thenThrow(new IllegalStateException("mongo down"));

            SourceTBoxGate.BaselineUnreadableException e = assertThrows(
                    SourceTBoxGate.BaselineUnreadableException.class, () -> provider.getRegistryForRealm(REALM));
            assertTrue(e.getMessage().contains("cannot be checked"), e.getMessage());
        });
    }

    @Test
    void eachBuilderIsCheckedAgainstItsOwnLastBuild() {
        withConfig(null, () -> {
            System.setProperty("quantum.ontology.tbox.builder", "orders-app");
            try {
                OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
                TenantOntologyRegistryProvider provider = sourceProvider(legacyRepo, null);
                // Another application sharing the realm built the stable relation; it is not this baseline.
                when(legacyRepo.findLatestForBuilder("billing-app")).thenReturn(Optional.of(baselineWithStableRelation(1)));

                assertTrue(provider.getRegistryForRealm(REALM).classOf("Order").isPresent());
                ArgumentCaptor<OntologyTBox> saved = ArgumentCaptor.forClass(OntologyTBox.class);
                verify(legacyRepo).save(saved.capture());
                assertEquals("orders-app", saved.getValue().getBuilder());
            } finally {
                System.clearProperty("quantum.ontology.tbox.builder");
            }
        });
    }

    @Test
    void aTBoxTheBuilderBuiltBeforeIsAdmittedAsARollback() {
        withConfig(null, () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            TenantOntologyRegistryProvider provider = sourceProvider(legacyRepo, baselineWithStableRelation(1));
            OntologyTBox earlier = new OntologyTBox();
            earlier.setVersion(1);
            when(legacyRepo.findByHashAndBuilder(anyString(), eq("default"))).thenReturn(Optional.of(earlier));

            assertTrue(provider.getRegistryForRealm(REALM).classOf("Order").isPresent());
            verify(legacyRepo).save(earlier);
            assertNotNull(earlier.getAppliedAt(), "the earlier build is re-stamped as the baseline");
        });
    }

    @Test
    void aRefusedRealmIsNotRescannedUntilTheRetryWindowPasses() {
        withConfig(null, () -> {
            OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
            TenantOntologyRegistryProvider provider = sourceProvider(legacyRepo, baselineWithStableRelation(1));

            SourceTBoxGate.StableContractException first = assertThrows(SourceTBoxGate.StableContractException.class,
                    () -> provider.getRegistryForRealm(REALM));
            SourceTBoxGate.StableContractException again = assertThrows(SourceTBoxGate.StableContractException.class,
                    () -> provider.getRegistryForRealm(REALM));
            assertSame(first, again);
            verify(legacyRepo, times(1)).findLatestForBuilder(anyString());

            provider.invalidateRealm(REALM);
            assertThrows(SourceTBoxGate.StableContractException.class, () -> provider.getRegistryForRealm(REALM));
            verify(legacyRepo, times(2)).findLatestForBuilder(anyString());
        });
    }

    @Test
    void anUnversionedRebuildKeepsTheRecordedVersion() {
        withConfig(null, () -> {
            System.setProperty("ontology.yaml.path", "src/test/resources/ontology-unversioned.yaml");
            try {
                OntologyTBoxRepo legacyRepo = mock(OntologyTBoxRepo.class);
                TenantOntologyRegistryProvider provider = sourceProvider(legacyRepo, null);
                OntologyTBox recorded = new OntologyTBox();
                recorded.setVersion(3);
                when(legacyRepo.findByHashAndBuilder(anyString(), eq("default"))).thenReturn(Optional.of(recorded));

                provider.getRegistryForRealm(REALM);
                verify(legacyRepo).save(recorded);
                assertEquals(3, recorded.getVersion());
            } finally {
                System.clearProperty("ontology.yaml.path");
            }
        });
    }
}

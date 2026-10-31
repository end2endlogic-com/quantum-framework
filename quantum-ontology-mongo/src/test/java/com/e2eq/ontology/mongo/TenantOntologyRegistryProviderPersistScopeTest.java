package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.model.OntologyTBox;
import com.e2eq.ontology.repo.OntologyTBoxRepo;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the TBox persist-scope fix.
 *
 * <p>{@code buildRegistryForRealm} must persist the freshly built <em>source</em> TBox even when
 * it is invoked from a thread with NO active CDI request/security context — i.e. the
 * {@code OntologyStartupInitializer} {@code "ontology-tbox-publisher"} daemon thread that runs at
 * boot, or a {@code @PostConstruct} startup path. Before the fix, the persist step dereferenced the
 * request-scoped {@link io.quarkus.security.identity.SecurityIdentity} proxy while resolving the
 * realm, which threw <em>"RequestScoped context was not active"</em>; the exception was swallowed
 * (only warned), so the source TBox was never persisted and every subsequent rebuild re-scanned
 * annotations/Morphia/YAML instead of loading the persisted {@link OntologyTBox}.</p>
 *
 * <p>This test reproduces the exact failing shape: it runs {@link TenantOntologyRegistryProvider#buildSourceRegistry(String)}
 * on a plain {@code new Thread} (no inherited request context) and asserts the realm's
 * {@code ontology_tbox} collection is populated afterwards. It FAILS against the pre-fix code (the
 * background persist throws and is swallowed, leaving the collection empty) and PASSES once the
 * persist runs under a realm-scoped SYSTEM {@link SecurityCallScope}.</p>
 */
@QuarkusTest
@TestProfile(TenantOntologyRegistryProviderPersistScopeTest.PersistEnabledProfile.class)
public class TenantOntologyRegistryProviderPersistScopeTest {

    /**
     * The shared test profile disables TBox persistence ({@code quantum.ontology.tbox.persist=false});
     * this fix is specifically about the persist step, so enable it for this class.
     */
    public static class PersistEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quantum.ontology.tbox.persist", "true");
        }
    }

    @Inject
    TenantOntologyRegistryProvider provider;

    @Inject
    OntologyTBoxRepo tboxRepo;

    @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
    String defaultRealm;

    @Test
    void persistsSourceTBoxFromNoContextBackgroundThread() throws Exception {
        // Deterministically clear + read the realm's ontology_tbox collection under a realm-scoped
        // SYSTEM context. This uses the SAME realm-deterministic DataDomain the fix uses to resolve
        // the realm without the request-scoped proxy, so write and read target the same collection.
        PrincipalContext principal = SecurityCallScope.service(
                defaultRealm, TenantOntologyRegistryProvider.realmDataDomain(defaultRealm), "test-fixture");
        ResourceContext resource = SecurityCallScope.writeResource(principal, defaultRealm, "ONTOLOGY", "TBOX");

        SecurityCallScope.runWithContexts(principal, resource, () -> tboxRepo.deleteAll());

        Optional<OntologyTBox> before =
                SecurityCallScope.runWithContexts(principal, resource, () -> tboxRepo.findLatest());
        assertTrue(before.isEmpty(), "precondition: no persisted TBox before the background build");

        // Reproduce the startup publisher: build the source registry on a PLAIN thread that has no
        // inherited CDI request/security context (SecurityContext thread-locals do not propagate to
        // child threads, and no request scope is active on a bare new Thread).
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<OntologyRegistry> built = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                SecurityContext.clear(); // belt-and-suspenders: guarantee no ambient context here
                built.set(provider.buildSourceRegistry(defaultRealm));
            } catch (Throwable e) {
                error.set(e);
            }
        }, "test-ontology-tbox-publisher");
        t.start();
        t.join(60_000);

        assertFalse(t.isAlive(), "background build thread did not finish in time");
        assertNull(error.get(), "build/persist from a no-context background thread must not throw");
        assertNotNull(built.get(), "build must return a registry");

        // The source TBox must now be persisted in the realm's ontology_tbox collection so a later
        // boot loads it instead of re-scanning. This is the assertion that fails pre-fix.
        Optional<OntologyTBox> after =
                SecurityCallScope.runWithContexts(principal, resource, () -> tboxRepo.findLatest());
        assertTrue(after.isPresent(),
                "source TBox must be persisted after a background-thread build "
                        + "(regression: RequestScoped context was not active while resolving the realm)");
    }
}

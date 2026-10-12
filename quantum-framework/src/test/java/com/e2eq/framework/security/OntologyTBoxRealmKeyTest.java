package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.e2eq.ontology.repo.TenantOntologyTBoxRepo;
import com.e2eq.ontology.runtime.RealmOntologyKeys;
import com.e2eq.ontology.runtime.TenantOntologyRegistryProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A realm's ontology key is its registered domain, so a tenant admin publishing a model writes into
 * their own tenant and passes the write filter check ("tenant admin can administer the tenant
 * records": dataDomain.tenantId:${pTenantId}). The old realm-name key could never match a user's
 * tenant, so every admin publish was refused.
 */
@QuarkusTest
public class OntologyTBoxRealmKeyTest extends BaseRepoTest {

    private static final String REALM = "test-ontokey-quantum-com";
    private static final String TENANT = "ontokey-quantum.com";
    private static final String ORG = "ontokey.com";
    private static final String ACCOUNT = "0000000042";

    @Inject
    RealmRepo realmRepo;

    @Inject
    TenantOntologyTBoxRepo tboxRepo;

    @Inject
    MorphiaDataStoreWrapper dataStores;

    @Inject
    com.e2eq.framework.util.EnvConfigUtils envConfigUtils;

    @BeforeEach
    void register() {
        SecurityContext.clear();
        cleanUp();
        DomainContext domain = DomainContext.builder()
                .tenantId(TENANT).orgRefName(ORG).accountId(ACCOUNT).defaultRealm(REALM).dataSegment(0).build();
        Realm realm = Realm.builder()
                .refName(REALM)
                .displayName("Ontology key test")
                .emailDomain(TENANT)
                .databaseName(REALM)
                .domainContext(domain)
                .dataDomain(new DataDomain(envConfigUtils.getSystemOrgRefName(), envConfigUtils.getSystemAccountNumber(),
                        envConfigUtils.getSystemTenantId(), 0, envConfigUtils.getSystemUserId()))
                .build();
        asSystem(() -> realmRepo.save(envConfigUtils.getSystemRealm(), realm));
        RealmOntologyKeys.refresh(REALM);
    }

    @AfterEach
    void cleanUp() {
        asSystem(() -> {
            realmRepo.findByRefName(REALM, true, envConfigUtils.getSystemRealm())
                    .ifPresent(r -> realmRepo.getMorphiaDataStoreWrapper().getDataStore(envConfigUtils.getSystemRealm())
                            .getDatabase().getCollection("realm").deleteOne(new Document("refName", REALM)));
            dataStores.getDataStore(REALM).getDatabase().getCollection("tenant_ontology_tbox").deleteMany(new Document());
            return null;
        });
        RealmOntologyKeys.refresh(REALM);
        SecurityContext.clear();
    }

    @Test
    public void theKeyIsTheRealmsRegisteredTenant() {
        DataDomain key = TenantOntologyRegistryProvider.realmDataDomain(REALM);
        assertEquals(TENANT, key.getTenantId());
        assertEquals(ORG, key.getOrgRefName());
        assertEquals(ACCOUNT, key.getAccountNum());
    }

    @Test
    public void aTenantAdminCanPublishUnderTheRealmKeyButNotTheOldOne() {
        DataDomain key = TenantOntologyRegistryProvider.realmDataDomain(REALM);
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(tenantAdmin(), apply())) {
            TenantOntologyTBox saved = tboxRepo.save(REALM, tbox("tbox-registered", key));
            assertNotNull(saved.getId(), "the admin's publish lands in their own tenant");
            assertThrows(SecurityCheckException.class,
                    () -> tboxRepo.save(REALM, tbox("tbox-legacy", RealmOntologyKeys.legacy(REALM))),
                    "the old realm-name key is not the admin's tenant");
        }
        assertTrue(asSystem(() -> tboxRepo.findByHash(key, "tbox-registered")).isPresent());
    }

    @Test
    public void modelsSavedUnderTheOldKeyMoveToTheRegisteredKey() {
        // Saved before the realm was registered: under the old realm-name key.
        TenantOntologyTBox old = tbox("tbox-old", RealmOntologyKeys.legacy(REALM));
        old.setActive(true);
        asSystem(() -> tboxRepo.save(REALM, old));
        RealmOntologyKeys.refresh(REALM);

        DataDomain key = TenantOntologyRegistryProvider.realmDataDomain(REALM);
        assertEquals("tbox-old", asSystem(() -> tboxRepo.findActiveTBoxHash(key)).orElse(null),
                "a realm that published before keeps its active model");
    }

    // ---------------------------------------------------------------------------- helpers

    private TenantOntologyTBox tbox(String hash, DataDomain key) {
        TenantOntologyTBox t = new TenantOntologyTBox();
        t.setRefName(hash + "-" + UUID.randomUUID());
        t.setTboxHash(hash);
        t.setAppliedAt(new Date());
        t.setDataDomain(key);
        return t;
    }

    private PrincipalContext tenantAdmin() {
        DataDomain mine = new DataDomain(ORG, ACCOUNT, TENANT, 0, "admin@" + TENANT);
        return SecurityCallScope.principal(REALM, mine, "admin@" + TENANT, "Authentication", "admin");
    }

    private ResourceContext apply() {
        return new ResourceContext.Builder().withRealm(REALM).withArea("ONTOLOGY").withFunctionalDomain("TBOX").withAction("APPLY").build();
    }

    /** System work, addressed to {@link #REALM} (repository reads resolve their database from the context realm). */
    private <T> T asSystem(java.util.function.Supplier<T> work) {
        PrincipalContext system = SecurityCallScope.principal(REALM, new DataDomain(envConfigUtils.getSystemOrgRefName(), envConfigUtils.getSystemAccountNumber(), envConfigUtils.getSystemTenantId(), 0, envConfigUtils.getSystemUserId()),
                testUtils.getSystemUserId(), "Authentication", roles);
        ResourceContext resource = new ResourceContext.Builder().withRealm(REALM)
                .withArea("SECURITY").withFunctionalDomain("REALM").withAction("SAVE").build();
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource);
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            return work.get();
        }
    }
}

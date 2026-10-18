package com.e2eq.framework.system;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.system.remote.ControlPlaneRealmMapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * Phase A exit test for the control-plane split
 * (docs/design/CONTROL_PLANE_SPLIT_DESIGN.md §9).
 *
 * Verifies the SystemDirectory indirection is in place and that, in local
 * mode, it resolves against the *configured* system realm rather than a
 * hard-coded constant. Flipping quantum.realmConfig.systemRealm (or running
 * an app with a per-app system realm such as system-psa-com) must be fully
 * reflected through this interface.
 */
@QuarkusTest
public class TestSystemDirectory extends BaseRepoTest {

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Inject
    RealmRepo realmRepo;

    @Test
    public void systemDirectoryInjectsInLocalModeByDefault() {
        Assertions.assertNotNull(systemDirectory, "SystemDirectory must be injectable (local mode default)");
    }

    @Test
    public void systemRealmIdFollowsConfiguredProperty() {
        // The pointer must come from configuration (quantum.realmConfig.systemRealm),
        // not a constant baked into call sites.
        Assertions.assertEquals(envConfigUtils.getSystemRealm(), systemDirectory.systemRealmId());
    }

    @Test
    public void realmCatalogLookupsResolveAgainstSystemRealm() {
        // The system user's credential is seeded into the system realm by the
        // baseline identity startup; the directory must find it without the
        // caller passing a realm constant.
        Optional<CredentialUserIdPassword> credential =
            systemDirectory.findCredentialByUserId(envConfigUtils.getSystemUserId());
        Assertions.assertTrue(
            credential.isPresent(),
            "System user credential should resolve via SystemDirectory in realm " + systemDirectory.systemRealmId());
    }

    @Test
    public void unknownRealmLookupIsEmptyNotError() {
        Optional<Realm> realm = systemDirectory.findRealmByRefName("no-such-realm-xyz");
        Assertions.assertTrue(realm.isEmpty());
    }

    /**
     * PUT /control/realms/{refName} twice: the control-plane resource maps the
     * catalog entry (no id) and calls registerRealm. Both writes must land on one
     * document, the second write must be what a GET returns, and fields the catalog
     * entry does not carry must survive.
     */
    @Test
    public void registeringARealmTwiceByRefNameUpdatesOneDocument() {
        String refName = "test-realm-upsert-com";
        String systemRealm = systemDirectory.systemRealmId();
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            deleteRealms(refName);
            Realm provisioned = Realm.builder()
                .refName(refName)
                .displayName("Provisioned")
                .emailDomain(refName)
                .databaseName(refName)
                .defaultAdminUserId("owner@test-realm-upsert.com")
                .defaultPerspective("operator")
                .tenancyMode(RealmTenancyMode.MULTI_TENANT)
                .deploymentType(RealmDeploymentType.SHARED)
                .applicationRef(EntityReference.builder().entityType("Application")
                    .entityRefName("upsert-app").entityDisplayName("upsert-app").build())
                .domainContext(DomainContext.builder().tenantId("provisioned-tenant")
                    .orgRefName("provisioned-org").accountId("0000000007")
                    .defaultRealm("provisioned-home").dataSegment(2).build())
                .build();
            Realm inserted = systemDirectory.registerRealm(provisioned);

            systemDirectory.registerRealm(ControlPlaneRealmMapper.fromEntry(catalogEntry(refName, "First PUT")));
            systemDirectory.registerRealm(ControlPlaneRealmMapper.fromEntry(catalogEntry(refName, "Second PUT")));

            List<Realm> documents = realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm);
            Assertions.assertEquals(1, documents.size(), () -> "one realm document per refName, found "
                + documents.stream().map(realm -> String.valueOf(realm.getId())).toList());
            Assertions.assertEquals(inserted.getId(), documents.get(0).getId(), "the upsert keeps the recorded id");

            RealmCatalogEntry got = ControlPlaneRealmMapper.toEntry(
                systemDirectory.findRealmByRefName(refName).orElseThrow());
            Assertions.assertEquals("Second PUT", got.getDisplayName());
            Assertions.assertEquals("put-tenant", got.getTenantId());
            Assertions.assertEquals("put-org", got.getOrgRefName());
            Assertions.assertEquals("0000000001", got.getAccountNumber());

            Realm stored = documents.get(0);
            Assertions.assertEquals("owner@test-realm-upsert.com", stored.getDefaultAdminUserId());
            Assertions.assertEquals("operator", stored.getDefaultPerspective());
            Assertions.assertEquals("upsert-app", stored.getApplicationRef().getEntityRefName());
            Assertions.assertEquals(RealmTenancyMode.MULTI_TENANT, stored.getTenancyMode());
            Assertions.assertEquals(RealmDeploymentType.SHARED, stored.getDeploymentType());
            Assertions.assertEquals("provisioned-home", stored.getDomainContext().getDefaultRealm());
            Assertions.assertEquals(2, stored.getDomainContext().getDataSegment());
        } finally {
            try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
                deleteRealms(refName);
            }
        }
    }

    /** The body reconcile_development_control_plane.py sends: no admin, owner, tenancy or topology. */
    private static RealmCatalogEntry catalogEntry(String refName, String displayName) {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName(refName);
        entry.setDisplayName(displayName);
        entry.setDatabaseName(refName);
        entry.setEmailDomain(refName);
        entry.setTenantId("put-tenant");
        entry.setOrgRefName("put-org");
        entry.setAccountNumber("0000000001");
        return entry;
    }

    private void deleteRealms(String refName) {
        String systemRealm = systemDirectory.systemRealmId();
        for (Realm realm : realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm)) {
            try {
                realmRepo.delete(systemRealm, realm);
            } catch (Exception error) {
                throw new IllegalStateException("could not remove test realm " + realm.getId(), error);
            }
        }
    }
}

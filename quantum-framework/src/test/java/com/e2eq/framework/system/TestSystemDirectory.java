package com.e2eq.framework.system;

import com.e2eq.framework.api.system.RealmRegistrationConflictException;
import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.RealmRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.security.runtime.SecuritySession;
import com.e2eq.framework.system.remote.ControlPlaneRealmMapper;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
     * catalog entry (no id) and calls registerRealm. Writers round-trip the
     * recorded entry (GET, modify, PUT). Both writes must land on one document,
     * the second write must be what a GET returns, fields the catalog entry does
     * not carry must survive, and a write that would move the realm's tenancy is
     * refused without inserting or changing anything.
     */
    @Test
    public void registeringARealmTwiceByRefNameUpdatesOneDocument() {
        String refName = uniqueRefName();
        String systemRealm = systemDirectory.systemRealmId();
        List<ObjectId> created = new ArrayList<>();
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
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
            created.add(inserted.getId());

            systemDirectory.registerRealm(ControlPlaneRealmMapper.fromEntry(roundTrip(refName, "First PUT")));
            systemDirectory.registerRealm(ControlPlaneRealmMapper.fromEntry(roundTrip(refName, "Second PUT")));

            RealmCatalogEntry moved = roundTrip(refName, "Moved");
            moved.setTenantId("someone-else");
            Assertions.assertThrows(RealmRegistrationConflictException.class,
                () -> systemDirectory.registerRealm(ControlPlaneRealmMapper.fromEntry(moved)));

            List<Realm> documents = realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm);
            Assertions.assertEquals(1, documents.size(), () -> "one realm document per refName, found "
                + documents.stream().map(realm -> String.valueOf(realm.getId())).toList());
            Assertions.assertEquals(inserted.getId(), documents.get(0).getId(), "the upsert keeps the recorded id");

            RealmCatalogEntry got = ControlPlaneRealmMapper.toEntry(
                systemDirectory.findRealmByRefName(refName).orElseThrow());
            Assertions.assertEquals("Second PUT", got.getDisplayName());
            Assertions.assertEquals("provisioned-tenant", got.getTenantId());
            Assertions.assertEquals("provisioned-org", got.getOrgRefName());
            Assertions.assertEquals("0000000007", got.getAccountNumber());

            Realm stored = documents.get(0);
            Assertions.assertEquals("owner@test-realm-upsert.com", stored.getDefaultAdminUserId());
            Assertions.assertEquals("operator", stored.getDefaultPerspective());
            Assertions.assertEquals("upsert-app", stored.getApplicationRef().getEntityRefName());
            Assertions.assertEquals(RealmTenancyMode.MULTI_TENANT, stored.getTenancyMode());
            Assertions.assertEquals(RealmDeploymentType.SHARED, stored.getDeploymentType());
            Assertions.assertEquals("provisioned-home", stored.getDomainContext().getDefaultRealm());
            Assertions.assertEquals(2, stored.getDomainContext().getDataSegment());
        } finally {
            deleteCreated(refName, created);
        }
    }

    /**
     * Duplicates already in the database (written before registration was an upsert, or
     * where the unique index was never built): every lookup and the upsert use the
     * document with the lowest _id, whatever order they were inserted in, the upsert
     * updates only that one, and nothing is deleted.
     */
    @Test
    public void duplicateRealmDocumentsResolveToTheLowestIdAndAreNotDeleted() {
        String refName = uniqueRefName();
        String systemRealm = systemDirectory.systemRealmId();
        ObjectId lower = new ObjectId();
        ObjectId higher = new ObjectId();
        List<ObjectId> created = new ArrayList<>(List.of(lower, higher));
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            // Insert the higher id first, so insertion order and _id order disagree.
            // Seeded as the framework seeds catalog records (rules ignored), since the
            // fixtures stand for documents some other writer already left behind.
            try (SecurityCallScope.Scope seeding = SecurityCallScope.openIgnoringRules()) {
                realmRepo.save(systemRealm, duplicate(refName, higher, "Higher", "dup-tenant-higher"));
                realmRepo.save(systemRealm, duplicate(refName, lower, "Lower", "dup-tenant-lower"));
            }

            List<Realm> documents = realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm);
            Assertions.assertEquals(List.of(lower, higher), documents.stream().map(Realm::getId).toList());
            Assertions.assertEquals(lower, systemDirectory.findRealmByRefName(refName).orElseThrow().getId());

            systemDirectory.registerRealm(ControlPlaneRealmMapper.fromEntry(roundTrip(refName, "Renamed")));

            List<Realm> after = realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm);
            Assertions.assertEquals(2, after.size(), "registration must not delete or insert duplicates");
            Assertions.assertEquals("Renamed", after.get(0).getDisplayName());
            Assertions.assertEquals(lower, after.get(0).getId());
            Assertions.assertEquals("Higher", after.get(1).getDisplayName());
        } finally {
            deleteCreated(refName, created);
        }
    }

    private static Realm duplicate(String refName, ObjectId id, String displayName, String tenantId) {
        Realm realm = Realm.builder()
            .refName(refName)
            .displayName(displayName)
            // emailDomain and databaseName carry their own unique indexes.
            .emailDomain(tenantId + "." + refName)
            .databaseName(tenantId + "-" + refName)
            .defaultAdminUserId("owner@" + refName)
            .domainContext(DomainContext.builder().tenantId(tenantId)
                .orgRefName(tenantId).accountId("0000000001").defaultRealm(refName).build())
            .build();
        realm.setId(id);
        // A distinct data domain per document, as earlier duplicates had; the unique
        // refName index is per data domain.
        realm.setDataDomain(new com.e2eq.framework.model.persistent.base.DataDomain(
            tenantId, "0000000001", tenantId, 0, "system@system.com"));
        return realm;
    }

    /** A refName no other run or test uses, so cleanup can never touch another fixture. */
    private static String uniqueRefName() {
        return "test-realm-upsert-" + new ObjectId().toHexString().substring(12) + "-com";
    }

    /** What a round-tripping writer sends: the recorded catalog entry with a new display name. */
    private RealmCatalogEntry roundTrip(String refName, String displayName) {
        RealmCatalogEntry entry = ControlPlaneRealmMapper.toEntry(
            systemDirectory.findRealmByRefName(refName).orElseThrow());
        entry.setDisplayName(displayName);
        return entry;
    }

    /** Deletes exactly the documents this test created and checks they are gone. */
    private void deleteCreated(String refName, List<ObjectId> created) {
        String systemRealm = systemDirectory.systemRealmId();
        try (final SecuritySession ignored = new SecuritySession(pContext, rContext);
             SecurityCallScope.Scope cleanup = SecurityCallScope.openIgnoringRules()) {
            for (Realm realm : realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm)) {
                if (!created.contains(realm.getId())) {
                    continue;
                }
                try {
                    realmRepo.delete(systemRealm, realm);
                } catch (Exception error) {
                    throw new IllegalStateException("could not remove test realm " + realm.getId(), error);
                }
            }
            List<ObjectId> left = realmRepo.findAllByRefNameWithIgnoreRules(refName, systemRealm).stream()
                .map(Realm::getId).filter(created::contains).toList();
            Assertions.assertTrue(left.isEmpty(), () -> "test realms left behind: " + left);
        }
    }
}

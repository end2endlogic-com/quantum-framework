package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under a remote directory the realm catalog, realm memberships and user realm roles belong to the
 * directory owner: every write to this service's copy is refused with DIRECTORY_REMOTE before any
 * datastore is touched (no datastore is given here), so the copy cannot drift from the owner's.
 */
class DirectoryOwnershipWriteGuardTest {

    @Test
    void theRecordsAreOwnedHereByDefault() {
        DirectoryOwnership unrouted = new DirectoryOwnership();
        unrouted.init();
        assertTrue(unrouted.catalogOwnedHere(), "without quantum-system there is no remote directory");
        assertTrue(unrouted.membershipsOwnedHere());
        unrouted.requireMembershipsOwnedHere("realm memberships");
        unrouted.requireCatalogOwnedHere("realm catalog entries");
        assertTrue(ownership(false).catalogOwnedHere());
        assertTrue(ownership(false).membershipsOwnedHere());
        new RealmRepo().requireWritable(); // a repo outside CDI has no routing: local
    }

    @Test
    void aRemoteDirectoryRefusesEveryWriteToTheRealmCatalog() {
        RealmRepo repo = new RealmRepo();
        repo.directoryOwnership = ownership(true);
        assertRefused(() -> repo.save((dev.morphia.Datastore) null, new Realm()));
        assertRefused(() -> repo.save((dev.morphia.Datastore) null, List.of(new Realm())));
        assertRefused(() -> repo.merge((dev.morphia.Datastore) null, new Realm()));
        assertRefused(() -> repo.updateActiveStatus((dev.morphia.Datastore) null, new ObjectId(), ActiveStatus.INACTIVE));
        assertRefused(() -> repo.updateManyByIds((dev.morphia.Datastore) null, List.of(new ObjectId()), true, Pair.of("displayName", "x")));
        assertRefused(() -> repo.delete((dev.morphia.transactions.MorphiaSession) null, withId(new Realm())));
        assertRefused(() -> repo.delete("system-com", new ObjectId()));
    }

    @Test
    void aRemoteDirectoryRefusesWritesToMembershipsAndUserRealmRoles() {
        RealmTenantMembershipRepo memberships = new RealmTenantMembershipRepo();
        memberships.directoryOwnership = ownership(true);
        assertRefused(() -> memberships.save((dev.morphia.Datastore) null, new RealmTenantMembership()));
        assertRefused(() -> memberships.delete((dev.morphia.transactions.MorphiaSession) null, withId(new RealmTenantMembership())));

        UserRealmRoleRepo roles = new UserRealmRoleRepo();
        roles.directoryOwnership = ownership(true);
        assertRefused(() -> roles.save((dev.morphia.Datastore) null, new UserRealmRole()));
        assertRefused(() -> roles.updateActiveStatus((dev.morphia.Datastore) null, new ObjectId(), ActiveStatus.INACTIVE));
    }

    @Test
    void aLocalDirectoryKeepsItsWrites() {
        DirectoryOwnership local = ownership(false);
        assertTrue(local.catalogOwnedHere());
        assertTrue(local.membershipsOwnedHere());
        RealmRepo repo = new RealmRepo();
        repo.directoryOwnership = local;
        repo.requireWritable();
    }

    /**
     * The older catalog-only setting (quantum.system.directory.mode=remote, quantum.directory.mode
     * unset): the owner holds the realm catalog, this service still holds the memberships. Catalog
     * writes are refused; membership and user realm role writes are not.
     */
    @Test
    void aCatalogOnlyRemoteRefusesCatalogWritesAndKeepsMembershipWrites() {
        DirectoryOwnership catalogOnly = new DirectoryOwnership();
        catalogOnly.routing(new DirectoryRouting() {
            @Override
            public boolean membershipsRemote() {
                return false;
            }

            @Override
            public boolean catalogRemote() {
                return true;
            }
        });
        assertFalse(catalogOnly.catalogOwnedHere());
        assertTrue(catalogOnly.membershipsOwnedHere());

        RealmRepo realms = new RealmRepo();
        realms.directoryOwnership = catalogOnly;
        assertRefused(() -> realms.save((dev.morphia.Datastore) null, new Realm()));
        assertRefused(realms::requireWritable);

        RealmTenantMembershipRepo memberships = new RealmTenantMembershipRepo();
        memberships.directoryOwnership = catalogOnly;
        memberships.requireWritable();
        UserRealmRoleRepo roles = new UserRealmRoleRepo();
        roles.directoryOwnership = catalogOnly;
        roles.requireWritable();
    }

    @Test
    void aRoutingThatDoesNotRouteTheCatalogAloneHoldsItWithTheMemberships() {
        DirectoryRouting together = () -> true;
        assertTrue(together.catalogRemote());
        assertFalse(ownership(true).catalogOwnedHere());
    }

    private static void assertRefused(Executable write) {
        ApplicationAccessException refused = assertThrows(ApplicationAccessException.class, write);
        assertEquals(ApplicationAccessException.Code.DIRECTORY_REMOTE, refused.code());
    }

    private static <T extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> T withId(T model) {
        model.setId(new ObjectId());
        return model;
    }

    private static DirectoryOwnership ownership(boolean remote) {
        DirectoryOwnership ownership = new DirectoryOwnership();
        ownership.routing(() -> remote);
        return ownership;
    }
}

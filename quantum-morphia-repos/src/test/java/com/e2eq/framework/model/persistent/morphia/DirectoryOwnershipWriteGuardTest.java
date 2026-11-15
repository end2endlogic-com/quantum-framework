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
        assertTrue(unrouted.ownedHere(), "without quantum-system there is no remote directory");
        unrouted.requireOwnedHere("realm memberships");
        assertTrue(ownership(false).ownedHere());
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
        assertTrue(local.ownedHere());
        RealmRepo repo = new RealmRepo();
        repo.directoryOwnership = local;
        repo.requireWritable();
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

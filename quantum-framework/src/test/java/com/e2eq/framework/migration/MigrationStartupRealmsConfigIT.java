package com.e2eq.framework.migration;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MigrationStartupRealmsTestProfile.class)
public class MigrationStartupRealmsConfigIT {

    @Inject
    MigrationService migrationService;

    @Inject
    MongoClient mongoClient;

    @Test
    public void configuredStartupRealmIsMigratedBeforeSeedingPhase() {
        String realm = MigrationStartupRealmsTestProfile.EXTRA_REALM;
        mongoClient.getDatabase(realm).drop();

        migrationService.initializeStartupRealms();

        Assertions.assertTrue(
                migrationService.getCurrentDatabaseVersion(realm).isPresent(),
                "Configured startup migration realm should be initialized before seed startup evaluation");
    }
}

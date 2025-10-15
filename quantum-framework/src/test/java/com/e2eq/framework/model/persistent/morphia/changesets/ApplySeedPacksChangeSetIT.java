package com.e2eq.framework.model.persistent.morphia.changesets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
public class ApplySeedPacksChangeSetIT {

    private static final String REALM = "seed-changeset-it";

    @Inject
    MongoClient mongoClient;

    @Inject
    MigrationService migrationService;


    @BeforeEach
    void cleanRealm() {
        // Ensure a clean realm DB for this test
        mongoClient.getDatabase(REALM).drop();
    }

    @Test
    void applySeedPacksChangeSetIsRemoved() {
        // Verify the change set is NOT discoverable by MigrationService anymore
        List<ChangeSetBean> all = migrationService.getAllChangeSetBeans();
        List<String> names = all.stream().map(ChangeSetBean::getName).collect(Collectors.toList());
        assertTrue(!names.contains("Apply Seed Packs"), "Did not expect to find 'Apply Seed Packs' change set but found: " + names);
    }
}

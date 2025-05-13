package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.ParentModel;
import com.mongodb.assertions.Assertions;
import dev.morphia.MorphiaDatastore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestMorphiaDataStore extends BaseRepoTest{
    @Inject
    MorphiaDataStore dataStore;


    @Test
    public void testDifferentRealms() {
        // create two datastores for different realms
        MorphiaDatastore realm1DataStore = dataStore.getDataStore("test-realm1");
        MorphiaDatastore realm2DataStore = dataStore.getDataStore("test-realm2");

        try(final SecuritySession ignored = new SecuritySession(pContext, rContext)) {
            ParentModel parent1 = new ParentModel();
            parent1.setRefName("parent1");
            parent1.setTestField("parent1");
            realm1DataStore.save(parent1);

            ParentModel parent2 = new ParentModel();
            parent2.setRefName("parent2");
            parent1.setTestField("parent2");
            realm2DataStore.save(parent2);

            Assertions.assertTrue(realm1DataStore.getDatabase().getName().equals("test-realm1"));
            Assertions.assertTrue(realm2DataStore.getDatabase().getName().equals("test-realm2"));

            realm1DataStore.getDatabase().drop();
            realm2DataStore.getDatabase().drop();
        }
    }

}

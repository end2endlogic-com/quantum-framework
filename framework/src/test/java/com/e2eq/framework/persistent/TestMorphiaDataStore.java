package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.test.ParentModel;
import org.junit.jupiter.api.Assertions;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
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

    @Test
    public void testConcurrentAccessCreatesSingleDatastore() throws Exception {
        String realm = "concurrent-test-realm";

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        Set<MorphiaDatastore> results = new HashSet<>();

        try {
            Set<Callable<MorphiaDatastore>> tasks = new HashSet<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> dataStore.getDataStore(realm));
            }

            for (Future<MorphiaDatastore> f : executor.invokeAll(tasks)) {
                results.add(f.get());
            }
        } finally {
            executor.shutdownNow();
        }

        Assertions.assertEquals(1, results.size());
        Assertions.assertTrue(dataStore.datastoreMap.containsKey(realm));

        dataStore.getDataStore(realm).getDatabase().drop();
    }

}

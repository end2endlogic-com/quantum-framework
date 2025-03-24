package com.e2eq.framework.migration;

import com.coditory.sherlock.DistributedLock;
import com.coditory.sherlock.Sherlock;
import com.coditory.sherlock.connector.AcquireResult;
import com.coditory.sherlock.migrator.ChangeSet;
import com.coditory.sherlock.migrator.SherlockMigrator;
import com.coditory.sherlock.mongo.MongoSherlock;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestLocks {
    @Inject
    MongoClient mongoClient;


    @Test
    public void testLocks() {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");

        Sherlock sherlock = MongoSherlock.create(collection);
        DistributedLock lock = sherlock.createLock("test-lock");
        // Acquire a lock, run action and finally release the lock
        lock.runLocked(() -> System.out.println("Lock granted!"));
    }

    @Test
    public void testLocks2() throws InterruptedException {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");

        Sherlock sherlock = MongoSherlock.create(collection);
        Runnable lockTask = () -> {
            DistributedLock lock = sherlock.createLock("test-lock");
            AcquireResult result = lock.runLocked(() -> System.out.println("Lock granted by " + Thread.currentThread().getName()));
            if (result.acquired()) {
                System.out.println("Lock acquired by " + Thread.currentThread().getName());
            } else {
                System.out.println("Unable to acquire lock by " + Thread.currentThread().getName());
            }
        };
        Thread thread1 = new Thread(lockTask, "Thread-1");
        Thread thread2 = new Thread(lockTask, "Thread-2");

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

    }

    public static class AnnotatedMigration {


        @ChangeSet(order = 0, id = "change-set-a")
        public void changeSetA() {
            Log.info("Annotated change-set: A");
        }

        @ChangeSet(order = 1, id = "change-set-b")
        public void changeSetB() {
            Log.info("Annotated change-set: B");
        }
    }

    public static class AnnotatedMigration2 {

        @ChangeSet(order = 0, id = "change-set-a")
        public void changeSetA() {
            Log.info("Annotated change-set: A");
        }

        @ChangeSet(order = 1, id = "change-set-b")
        public void changeSetB() {
            Log.info("Annotated change-set: B");
        }

        @ChangeSet(order = 2, id = "change-set-c")
        public void changeSetC() {
            Log.info("Annotated change-set: C");
        }
    }

    /**
     * Test the sherlock migrator facility
     */
    @Test
    public void testMigration() {
        MongoCollection<Document> collection = mongoClient
                .getDatabase("sherlock")
                .getCollection("locks");
        Sherlock sherlock = MongoSherlock.create(collection);
        // first commit - all migrations are executed
        SherlockMigrator.builder(sherlock)
                .addAnnotatedChangeSets(new AnnotatedMigration())
                .migrate();
        // second commit - only new change-set is executed
        SherlockMigrator.builder(sherlock)
                .addAnnotatedChangeSets(new AnnotatedMigration2())
                .migrate();
    }
}

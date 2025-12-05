package com.e2eq.framework.api.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoIterable;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class TestMongoDB {

    @Inject
    MongoClient mongoClient;

    @Test
    public void testInjection() {
        System.out.println("Hello World!");

        MongoIterable<String> it = mongoClient.listDatabaseNames();
        it.forEach(System.out::println);
    }

}

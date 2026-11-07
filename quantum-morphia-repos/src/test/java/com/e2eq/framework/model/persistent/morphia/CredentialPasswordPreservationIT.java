package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClients;
import dev.morphia.Morphia;
import dev.morphia.config.ManualMorphiaConfig;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real persisted credential -> public JSON -> metadata update -> persistence. */
class CredentialPasswordPreservationIT {
    @Test void publicReadRoundTripPreservesStoredPassword() throws Exception {
        String database="test-password-preservation-"+UUID.randomUUID().toString().replace("-", "");
        var settings=com.mongodb.MongoClientSettings.builder()
            .applyConnectionString(new com.mongodb.ConnectionString(System.getProperty("quantum.test.mongo-uri", "mongodb://127.0.0.1:27017/?replicaSet=rs0")))
            .codecRegistry(org.bson.codecs.configuration.CodecRegistries.fromRegistries(
                com.mongodb.MongoClientSettings.getDefaultCodecRegistry(),
                org.bson.codecs.configuration.CodecRegistries.fromProviders(org.bson.codecs.pojo.PojoCodecProvider.builder().automatic(true).build())))
            .build();
        try(var client=MongoClients.create(settings)) {
            assertNotNull(client.getDatabase("admin").runCommand(new org.bson.Document("hello",1)).get("setName"));
            var ds=(dev.morphia.MorphiaDatastore) Morphia.createDatastore(client,ManualMorphiaConfig.configure().database(database));
            ds.getMapper().mapPackage("com.e2eq.framework.model.persistent.base");
            ds.getMapper().mapPackage("com.e2eq.framework.model.security");
            try {
                var original=new CredentialUserIdPassword(); original.setRefName("roundtrip"); original.setUserId("roundtrip"); original.setSubject("roundtrip");
                original.setDomainContext(com.e2eq.framework.model.security.DomainContext.builder().tenantId("fixture").defaultRealm(database).orgRefName("fixture").accountId("fixture").build());
                original.setLastUpdate(new java.util.Date());
                original.setPasswordHash("persisted-password-hash"); original.setHashingAlgorithm("legacy-algorithm"); original.setPasswordRevision(9L);
                ds.save(original);
                ObjectMapper json=new ObjectMapper();
                var ids=new com.fasterxml.jackson.databind.module.SimpleModule();
                ids.addSerializer(org.bson.types.ObjectId.class, new com.e2eq.framework.rest.models.ObjectIdJsonSerializer());
                ids.addDeserializer(org.bson.types.ObjectId.class, new com.fasterxml.jackson.databind.JsonDeserializer<org.bson.types.ObjectId>() {
                    @Override public org.bson.types.ObjectId deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                        return new org.bson.types.ObjectId(parser.getValueAsString());
                    }
                });
                json.registerModule(ids);
                String wire=json.writeValueAsString(original);
                assertFalse(wire.contains("persisted-password-hash"));
                var incoming=json.readValue(wire,CredentialUserIdPassword.class);
                assertNull(incoming.getPasswordHash()); incoming.setRoles(new String[]{"user","member"});
                new CredentialRepo().reconcileServerOwnedState(ds,incoming,RefNameContract.WriteMode.REPLACE);
                ds.save(incoming);
                var actual=ds.find(CredentialUserIdPassword.class).first();
                assertEquals(original.getPasswordHash(),actual.getPasswordHash());
                assertEquals(original.getHashingAlgorithm(),actual.getHashingAlgorithm());
                assertEquals(9L,actual.currentPasswordRevision());
                assertArrayEquals(new String[]{"user","member"},actual.getRoles());
            } finally { client.getDatabase(database).drop(); }
        }
    }
}

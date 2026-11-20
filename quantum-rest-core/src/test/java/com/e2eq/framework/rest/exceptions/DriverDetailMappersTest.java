package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.rest.models.RestError;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import jakarta.ws.rs.core.Response;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression for the re-review's mapper probe: a MongoDB write error (host, database, collection,
 * index and the duplicate key's value, which would enumerate accounts) and a checked exception's
 * message never reach the client unless debug detail is enabled.
 */
class DriverDetailMappersTest {

    private static final String DRIVER_MESSAGE = "E11000 duplicate key error collection: quantum-auth.credentialUserIdPassword"
            + " index: userId_1 dup key: { userId: \"victim@example.com\" }";

    @Test
    void aDuplicateKeyIsAGenericConflictNamingNoKeyHostOrIndex() {
        MongoWriteException duplicate = new MongoWriteException(new WriteError(11000, DRIVER_MESSAGE, new BsonDocument()),
                new ServerAddress("mongo-prod.internal", 27017), Set.of());
        Response response = new WriteErrorExceptionMapper().toResponse(duplicate);
        RestError error = (RestError) response.getEntity();
        assertEquals(400, response.getStatus());
        assertEquals(WriteErrorExceptionMapper.DUPLICATE_KEY_REASON, error.getReasonMessage());
        assertNull(error.getDebugMessage());
        assertNoInternals(error);
    }

    @Test
    void anyOtherWriteErrorNamesNoInternals() {
        MongoWriteException failed = new MongoWriteException(new WriteError(121, "Document failed validation in quantum-auth.x",
                new BsonDocument()), new ServerAddress("mongo-prod.internal", 27017), Set.of());
        RestError error = (RestError) new WriteErrorExceptionMapper().toResponse(failed).getEntity();
        assertEquals(WriteErrorExceptionMapper.WRITE_ERROR_REASON, error.getReasonMessage());
        assertNull(error.getDebugMessage());
        assertNoInternals(error);
    }

    @Test
    void aCheckedExceptionsMessageIsNotReturned() {
        Response response = new JsonExceptionMapper().toResponse(
                new IOException("Connection refused: mongo-prod.internal/10.0.0.7:27017 db=quantum-auth"));
        RestError error = (RestError) response.getEntity();
        assertEquals(500, response.getStatus());
        assertEquals(ClientErrorDetail.UNEXPECTED_ERROR_MESSAGE, error.getStatusMessage());
        assertNull(error.getDebugMessage());
        assertNoInternals(error);
    }

    private static void assertNoInternals(RestError error) {
        String all = String.valueOf(error.getStatusMessage()) + error.getReasonMessage() + error.getDebugMessage();
        for (String internal : new String[]{"mongo-prod", "victim@example.com", "quantum-auth", "userId_1", "10.0.0.7"}) {
            assertFalse(all.contains(internal), internal + " in " + all);
        }
    }
}

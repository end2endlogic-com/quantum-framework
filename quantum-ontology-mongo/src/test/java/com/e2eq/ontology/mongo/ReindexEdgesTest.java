package com.e2eq.ontology.mongo;

import com.e2eq.ontology.service.OntologyReindexer;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One-shot utility test to trigger a full ontology edge reindex for a realm.
 * Iterates all @OntologyClass entities, extracts edges from @OntologyProperty fields,
 * and materializes them (explicit + inferred + computed).
 *
 * Usage:
 *   # Reindex the default realm (types-test):
 *   mvn test -pl quantum-ontology-mongo -Dtest=ReindexEdgesTest -am
 *
 *   # Reindex a specific realm:
 *   mvn test -pl quantum-ontology-mongo -Dtest=ReindexEdgesTest -am -Dreindex.realm=mycompany-com
 */
@QuarkusTest
public class ReindexEdgesTest {

    @Inject
    OntologyReindexer reindexer;

    @ConfigProperty(name = "reindex.realm", defaultValue = "types-test")
    String realm;

    @Test
    void reindexEdges() throws InterruptedException {
        Log.infof("Triggering ontology edge reindex for realm: %s (force=true)", realm);
        reindexer.runAsync(realm, true);

        // Wait for the async reindex to complete
        int maxWaitSeconds = 300;
        for (int i = 0; i < maxWaitSeconds; i++) {
            Thread.sleep(1000);
            String status = reindexer.status();
            if (status.startsWith("COMPLETED") || status.startsWith("FAILED")) {
                Log.infof("Reindex finished with status: %s", status);
                break;
            }
            if (i % 10 == 0) {
                Log.infof("Waiting for reindex... status=%s, elapsed=%ds", status, i);
            }
        }

        String finalStatus = reindexer.status();
        Log.infof("Final status: %s", finalStatus);
        assertEquals("COMPLETED", finalStatus, "Reindex should complete successfully");
    }
}

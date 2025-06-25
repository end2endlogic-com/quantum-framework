package com.e2eq.framework.api.system;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.fail;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
public class SystemAPITest extends BaseRepoTest {
   @Inject
   TestUtils testUtils;
   @Inject
   MigrationService migrationService;

   @Test
   public void testMigration() {
      AtomicBoolean failed= new AtomicBoolean(false);
      try ( final SecuritySession ss = new SecuritySession(pContext, rContext)) {
         Multi.createFrom().emitter(emitter -> {
            migrationService.runAllUnRunMigrations(testUtils.getDefaultRealm(), emitter);
         }).subscribe().with(item -> {
            System.out.println(item);
         },
         failure -> {
            System.err.println("Failed: " + failure.getMessage());
             failed.set(true);
            },
         () -> System.out.println("Migration Complete"));
      }

      if (failed.get()) {
         fail("Migration failed");
      }

   }
    @Test
    public void testMapping() {
            given()
               //.header("X-Realm", testUtils.getTestRealm())
                    .when().get("/system/mapping")
                    .prettyPeek()
                    .then()
                    .statusCode(200);
    }
}

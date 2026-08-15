package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import io.quarkus.arc.properties.IfBuildProperty;

@Path("/system/migration")
@RolesAllowed({"admin", "system"})
@IfBuildProperty(name = "quantum.system-rest.enabled", stringValue = "true", enableIfMissing = true) // control-plane admin surface; one-switch opt-out (CONTROL_PLANE_SPLIT_DESIGN.md Phase B, wp3 tier 1)
public class MigrationResource {
   @Inject
   Executor managedExecutor;

   @ConfigProperty(name = "quantum.database.migration.enabled", defaultValue = "true")
   boolean enabled;

   @ConfigProperty(name = "quantum.migration.application-id")
   Optional<String> migrationApplicationId;

   @Inject
   MigrationService migrationService;

   @Inject
   EnvConfigUtils envConfigUtils;

   @Inject
   SecurityUtils securityUtils;

   @Inject
   RuleContext ruleContext;

   @Inject
   DatabaseVersionRepo databaseVersionRepo;

   @Inject
   SecurityIdentity identity;

   @GET
   @Path("/dbversion/{realm}")
   @Produces(MediaType.APPLICATION_JSON)
   @PermitAll
   public DatabaseVersion getDatabaseVersion (@PathParam("realm") String realm) {
      Optional<DatabaseVersion> oversion = databaseVersionRepo.findCurrentVersion(realm);
      if (oversion.isPresent()) {
         return oversion.get();
      } else
         throw new NotFoundException(String.format("realm:%s not found", realm));
   }

   @POST
   @Path("/indexes/applyIndexes/{realm}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalMapping(area="MIGRATION", domain="INDEXES")
   @FunctionalAction(value = "APPLY_INDEXES", bypassDataScoping = true)
   public void applyIndexes (@Context HttpHeaders headers, @PathParam("realm") String realm) {
      migrationService.applyIndexes(realm);
   }

   @POST
   @Path("/indexes/applyIndexes/{realm}/{collection}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalMapping(area="MIGRATION", domain="INDEXES")
   @FunctionalAction(value = "APPLY_INDEXES_COLLECTION", bypassDataScoping = true)
   public void applyIndexesOnCollection (@Context HttpHeaders headers, @PathParam("realm") String realm, @PathParam(
      "collection") String collection) {
      migrationService.applyIndexes(realm, collection);
   }

   @POST
   @Path("/indexes/applyAllIndexes/{realm}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalMapping(area="MIGRATION", domain="INDEXES")
   @FunctionalAction(value = "APPLY_ALL_INDEXES", bypassDataScoping = true)
   public void applyAllIndexes (@Context HttpHeaders headers, @PathParam("realm") String realm) {
      migrationService.applyAllIndexes(realm);
   }

   @POST
   @Path("/indexes/dropAllIndexes/{realm}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalMapping(area="MIGRATION", domain="INDEXES")
   @FunctionalAction(value = "DROP_ALL_INDEXES", bypassDataScoping = true)
   public void dropIndexes (@Context HttpHeaders headers, @PathParam("realm") String realm) {
      migrationService.dropAllIndexes(realm);
   }

   @POST
   @Path("/indexes/drop/{realm}/{collection}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.APPLICATION_JSON)
   @FunctionalMapping(area="MIGRATION", domain="INDEXES")
   @FunctionalAction(value = "DROP_INDEX", bypassDataScoping = true)
   public void dropIndex (@Context HttpHeaders headers, @PathParam("realm") String realm,
                          @PathParam("collection") String collection) {
      migrationService.dropIndexOnCollection(realm, collection);
   }

   @POST
   @Path("/changeSet/execute/{realm}/{beanRefName}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.SERVER_SENT_EVENTS)
   @FunctionalMapping(area="MIGRATION", domain="CHANGE_SET")
   @FunctionalAction(value = "EXECUTE_CHANGE_SET", bypassDataScoping = true)
   public void executeChangeSet (@Context HttpHeaders headers, @PathParam("realm") String realm, @PathParam(
      "beanRefName") String beanRefName, @Context SseEventSink eventSink, @Context Sse sse) {
      if (!identity.isAnonymous() && identity.hasRole("admin")) {
         String applicationId = currentMigrationApplicationId();

         Multi.createFrom().publisher(runChangeBeanTask(beanRefName, realm, applicationId))
            .emitOn(managedExecutor)
            .subscribe().with(
               message -> {
                  if (!eventSink.isClosed()) {
                     eventSink.send(sse.newEvent(message));
                  }
               },
               failure -> {
                  if (!eventSink.isClosed()) {
                     eventSink.send(sse.newEvent("Error: " + failure.getMessage()));
                     eventSink.close();
                  }
               },
               () -> {
                  if (!eventSink.isClosed()) {
                     eventSink.send(sse.newEvent("Task completed"));
                     eventSink.close();
                  }
               }
            );
      }
   }

   @POST
   @Path("/initialize/{realm}")
   @Produces(MediaType.SERVER_SENT_EVENTS)
   @RolesAllowed({"admin", "system"})
   @FunctionalMapping(area="MIGRATION", domain="DATABASE")
   @FunctionalAction(value = "INITIALIZE", bypassDataScoping = true)
   public void initializeDatabase (@PathParam("realm") String realm, @Context SseEventSink eventSink, @Context Sse sse) {

      if (!identity.isAnonymous() && identity.hasRole("admin")) {
         String applicationId = currentMigrationApplicationId();

         Multi.createFrom().publisher(initializeDatabaseTask(realm, applicationId))
            .emitOn(managedExecutor)
            .subscribe().with(
               message -> {
                  if (!eventSink.isClosed()) {
                     eventSink.send(sse.newEvent(message));
                  }
               },
               failure -> {
                  if (!eventSink.isClosed()) {
                     eventSink.send(sse.newEvent("Error: " + failure.getMessage()));
                     eventSink.close();
                  }
               },
               () -> {
                  if (!eventSink.isClosed()) {
                     eventSink.send(sse.newEvent("Task completed"));
                     eventSink.close();
                  }
               }
            );
      }
   }

   @GET
   @Path("/start")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.SERVER_SENT_EVENTS)
   @FunctionalMapping(area="MIGRATION", domain="DATABASE")
   @FunctionalAction(value = "RUN_ALL", bypassDataScoping = true)
   public void startTask (@Context SseEventSink eventSink, @Context Sse sse) {
      String applicationId = currentMigrationApplicationId();
      Multi.createFrom().publisher(runMigrateAllTask(applicationId))
         .emitOn(managedExecutor)
         .subscribe().with(
            message -> {
               if (!eventSink.isClosed()) {
                  eventSink.send(sse.newEvent(message));
               }
            },
            failure -> {
               if (!eventSink.isClosed()) {
                  eventSink.send(sse.newEvent("Error: " + failure.getMessage()));
                  eventSink.close();
               }
            },
            () -> {
               if (!eventSink.isClosed()) {
                  eventSink.send(sse.newEvent("Task completed"));
                  eventSink.close();
               }
            }
         );
   }

   @GET
   @Path("/start/{realm}")
   @RolesAllowed({"admin", "system"})
   @Produces(MediaType.SERVER_SENT_EVENTS)
   @FunctionalMapping(area="MIGRATION", domain="DATABASE")
   @FunctionalAction(value = "RUN", bypassDataScoping = true)
   public void startSpecificTask (@PathParam("realm") String realm, @Context SseEventSink eventSink, @Context Sse sse) {
      String applicationId = currentMigrationApplicationId();
      Multi.createFrom().publisher(runMigrateSpecificTask(realm, applicationId))
         .emitOn(managedExecutor)
         .subscribe().with(
            message -> {
               if (!eventSink.isClosed()) {
                  eventSink.send(sse.newEvent(message));
               }
            },
            failure -> {
               if (!eventSink.isClosed()) {
                  eventSink.send(sse.newEvent("Error: " + failure.getMessage()));
                  eventSink.close();
               }
            },
            () -> {
               if (!eventSink.isClosed()) {
                  eventSink.send(sse.newEvent("Task completed"));
                  eventSink.close();
               }
            }
         );
   }

   private Multi<String> initializeDatabaseTask (String realm, String applicationId) {
      Objects.requireNonNull(realm);
      return Multi.createFrom().emitter(emitter -> {
         try {
            String[] roles = {"admin", "user"};
            ruleContext.ensureDefaultRules();
            securityUtils.setSecurityContext();
            try {
               runWithMigrationScope(realm, applicationId, "INITIALIZE", () -> {
                  Log.infof("----Running migrations for system realm:%s---- ", realm);
                  migrationService.runAllUnRunMigrations(realm, emitter);
               });
            } finally {
               securityUtils.clearSecurityContext();
            }

            emitter.complete();
         } catch (Throwable e) {
            emitter.fail(e);
         }
      });
   }

   private Multi<String> runChangeBeanTask (String beanRefName, String realm, String applicationId) {

      Objects.requireNonNull(beanRefName);
      return Multi.createFrom().emitter(emitter -> {
         try {
            String[] roles = {"admin", "user"};
            ruleContext.ensureDefaultRules();
            securityUtils.setSecurityContext();
            try {
               runWithMigrationScope(realm, applicationId, "EXECUTE_CHANGE_SET", () -> {
                  Log.infof("----Running changeSet for bean:%s---- ", beanRefName);
                  migrationService.runChangeSetBean(beanRefName, realm, emitter);
               });
            } finally {
               securityUtils.clearSecurityContext();
            }

            emitter.complete();
         } catch (Throwable e) {
            emitter.fail(e);
         }
      });
   }

   private Multi<String> runMigrateAllTask (String applicationId) {
      return Multi.createFrom().emitter(emitter -> {
         try {

            Log.warn("-----!!!  RUNNING ALL MIGRATION TASKS !!!!-----");
            String[] roles = {"admin", "user"};
            ruleContext.ensureDefaultRules();
            securityUtils.setSecurityContext();
            try {
               Log.info("----Running migrations for test realm:---- " + envConfigUtils.getTestRealm());
               runWithMigrationScope(envConfigUtils.getTestRealm(), applicationId, "RUN", () -> migrationService.runAllUnRunMigrations(envConfigUtils.getTestRealm(), emitter));
               Log.info("----Running migrations for system realm:---- " + envConfigUtils.getSystemRealm());
               runWithMigrationScope(envConfigUtils.getSystemRealm(), applicationId, "RUN", () -> migrationService.runAllUnRunMigrations(envConfigUtils.getSystemRealm(), emitter));
               if (!envConfigUtils.getSystemRealm().equals(envConfigUtils.getDefaultRealm())) {
                  Log.info("----Running migrations for Default realm:---- " + envConfigUtils.getDefaultRealm());
                  runWithMigrationScope(envConfigUtils.getDefaultRealm(), applicationId, "RUN", () -> migrationService.runAllUnRunMigrations(envConfigUtils.getDefaultRealm(), emitter));
               }
            } finally {
               securityUtils.clearSecurityContext();
            }

            emitter.complete();
         } catch (Throwable e) {
            emitter.fail(e);
         }
      });
   }

   private Multi<String> runMigrateSpecificTask (String realm, String applicationId) {
      return Multi.createFrom().emitter(emitter -> {
         try {

            Log.warn("-----!!!  Migrations ENABLED !!!!-----");
            String[] roles = {"admin", "user"};
            ruleContext.ensureDefaultRules();
            securityUtils.setSecurityContext();
            try {
               runWithMigrationScope(realm, applicationId, "RUN", () -> {
                  Log.info("----Running migrations for realm:---- " + realm);
                  migrationService.runAllUnRunMigrations(realm, emitter);
               });
            } finally {
               securityUtils.clearSecurityContext();
            }

            emitter.complete();
         } catch (Throwable e) {
            emitter.fail(e);
         }
	      });
	   }

   String currentMigrationApplicationId() {
      return SecurityContext.getPrincipalContext()
         .map(principal -> normalizeApplicationId(principal.getApplicationId()))
         .filter(value -> value != null)
         .orElseGet(() -> migrationApplicationId
            .map(this::normalizeApplicationId)
            .orElse(null));
   }

   private String normalizeApplicationId(String applicationId) {
      if (applicationId == null || applicationId.isBlank()) {
         return null;
      }
      return applicationId.trim().toLowerCase();
   }

   private void runWithMigrationScope(String realm, String applicationId, String action, MigrationOperation operation) throws Exception {
      ResourceContext migrationResource = new ResourceContext.Builder()
         .withRealm(realm)
         .withApplicationId(applicationId)
         .withArea("MIGRATION")
         .withFunctionalDomain("DATABASE")
         .withAction(action)
         .build();

      try (SecurityCallScope.Scope ignored = SecurityCallScope.openResourceOnly(migrationResource)) {
         operation.run();
      }
   }

   @FunctionalInterface
   private interface MigrationOperation {
      void run() throws Exception;
   }
	}

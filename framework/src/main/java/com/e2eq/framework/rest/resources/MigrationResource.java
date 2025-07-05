package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;
import com.e2eq.framework.model.securityrules.RuleContext;
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

import java.net.http.HttpRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

@Path("/system/migration")
public class MigrationResource {
    @Inject
    Executor managedExecutor;

    @ConfigProperty(name = "quantum.database.migration.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    MigrationService migrationService;

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
    public DatabaseVersion getDatabaseVersion(@PathParam("realm") String realm) {
        Optional<DatabaseVersion> oversion = databaseVersionRepo.findCurrentVersion(realm);
        if (oversion.isPresent()) {
            return oversion.get();
        } else
        throw new NotFoundException(String.format( "realm:%s not found", realm));
    }

    @POST
    @Path("/initialize/{realm}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void initializeDatabase( @PathParam("realm") String realm, SseEventSink eventSink, Sse sse) {

        if (!identity.isAnonymous() && identity.hasRole("admin")) {

            Multi.createFrom().publisher(initializeDatabaseTask(realm))
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
    @RolesAllowed("admin")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void startTask(SseEventSink eventSink, Sse sse) {
        Multi.createFrom().publisher(runMigrateAllTask())
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
    @RolesAllowed("admin")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void startSpecificTask(@PathParam("realm") String realm, SseEventSink eventSink, Sse sse) {
        Multi.createFrom().publisher(runMigrateSpecificTask(realm))
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

    private Multi<String> initializeDatabaseTask(String realm) {
        Objects.requireNonNull(realm);
        return Multi.createFrom().emitter(emitter -> {
           try {
               String[] roles = {"admin", "user"};
               ruleContext.ensureDefaultRules();
               securityUtils.setSecurityContext();
               try {
                   Log.infof("----Running migrations for system realm:%s---- ", realm);
                   migrationService.runAllUnRunMigrations(realm, emitter);
               }finally {
                   securityUtils.clearSecurityContext();
               }

                emitter.complete();
            } catch (Throwable e) {
                emitter.fail(e);
            }
        });
    }

    private Multi<String> runMigrateAllTask() {
        return Multi.createFrom().emitter(emitter -> {
            try {

                Log.warn("-----!!!  RUNNING ALL MIGRATION TASKS !!!!-----");
                String[] roles = {"admin", "user"};
                ruleContext.ensureDefaultRules();
                securityUtils.setSecurityContext();
                try {
                    Log.info("----Running migrations for test realm:---- " + securityUtils.getTestRealm());
                    migrationService.runAllUnRunMigrations(securityUtils.getTestRealm(), emitter);
                    Log.info("----Running migrations for system realm:---- " + securityUtils.getSystemRealm());
                    migrationService.runAllUnRunMigrations(securityUtils.getSystemRealm(), emitter);
                    if (!securityUtils.getSystemRealm().equals(securityUtils.getDefaultRealm())) {
                        Log.info("----Running migrations for Default realm:---- " + securityUtils.getDefaultRealm());
                        migrationService.runAllUnRunMigrations(securityUtils.getDefaultRealm(), emitter);
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

    private Multi<String> runMigrateSpecificTask(String realm) {
        return Multi.createFrom().emitter(emitter -> {
            try {

                Log.warn("-----!!!  Migrations ENABLED !!!!-----");
                String[] roles = {"admin", "user"};
                ruleContext.ensureDefaultRules();
                securityUtils.setSecurityContext();
                try {
                    Log.info("----Running migrations for realm:---- " + realm);
                    migrationService.runAllUnRunMigrations(realm, emitter);
                } finally {
                    securityUtils.clearSecurityContext();
                }

                emitter.complete();
            } catch (Throwable e) {
                emitter.fail(e);
            }
        });
    }
}

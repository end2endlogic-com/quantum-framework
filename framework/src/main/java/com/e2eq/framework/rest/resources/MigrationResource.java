package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.util.SecurityUtils;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @GET
    @Path("/start")
    @RolesAllowed("admin")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void startTask(SseEventSink eventSink, Sse sse) {
        Multi.createFrom().publisher(runLongTask())
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

    private Multi<String> runLongTask() {
        return Multi.createFrom().emitter(emitter -> {
            try {

                Log.warn("-----!!!  Migrations ENABLED !!!!-----");
                String[] roles = {"admin", "user"};
                ruleContext.ensureDefaultRules();
                securityUtils.setSecurityContext();
                try {
                    migrationService.runAllUnRunMigrations(securityUtils.getTestRealm(), emitter);
                    migrationService.runAllUnRunMigrations(securityUtils.getSystemRealm(), emitter);
                    migrationService.runAllUnRunMigrations(securityUtils.getDefaultRealm(), emitter);
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

package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.exceptions.DatabaseMigrationException;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

@Provider
public class MigrationCheckRequestFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    MigrationService migrationService;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        boolean failRequest=false;
        DatabaseMigrationException ex = null;

        if (requestContext.getUriInfo().getPath().contains("hello")) {
            return;
        }

        try {
            // check the realm .. so first see if the realm is overridden
            String realmOverride = (requestContext.getHeaders().get("X-Realm") != null ) ? requestContext.getHeaders().get("X-Realm").get(0) : null;

            if (realmOverride != null) {
                Log.debug("Realm override found: " + realmOverride);
                migrationService.checkInitialized(realmOverride);
            } else {
                migrationService.checkInitialized(envConfigUtils.getDefaultRealm());
            }
           migrationService.checkInitialized(envConfigUtils.getSystemRealm());

        } catch (DatabaseMigrationException e) {
            Log.warn("!! Migration check failed: " + e.getMessage());
            Log.warnf(" Execution of Request:%s caused this error", requestContext.getUriInfo().getPath());
            failRequest=true;
            ex = e;
        }

        if (failRequest && (requestContext.getUriInfo().getPath().contains("login") ||
                  requestContext.getUriInfo().getPath().contains("migration"))) {
            try {
                migrationService.checkInitialized(envConfigUtils.getSystemRealm());
            } catch (DatabaseMigrationException e) {
                Log.warn("!! System Migration check failed: " + e.getMessage());
                Log.warnf(" Execution of Request:%s caused this error", requestContext.getUriInfo().getPath());
            }
            failRequest=false;
        }

        if (failRequest) {
           throw ex;
        }
    }
}

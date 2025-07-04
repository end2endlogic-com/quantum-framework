package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.rest.exceptions.DatabaseMigrationException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.util.Optional;

@Provider
public class MigrationCheckRequestFilter implements ContainerRequestFilter {

    @Inject
    SecurityIdentity identity;

    @Inject
    MigrationService migrationService;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (identity == null && !requestContext.getUriInfo().getPath().contains("system") &&
                        !requestContext.getUriInfo().getPath().contains("login")) {

            migrationService.isMigrationRequired();

        }
    }
}

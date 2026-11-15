package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationCatalogEntry;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Comparator;
import java.util.List;

/**
 * The signed-out application catalog, for a launch pad before sign-in. Unauthenticated through
 * the framework's {@code @PermitAll} mechanism. It returns {@link ApplicationCatalogEntry} rows
 * only (name, version, lifecycle state, launch addresses and the public sign-in route) for
 * applications in a catalogued lifecycle state; nothing else on the application record is read
 * out. The registry is read from the system realm; no realm or tenant header applies.
 */
@Path("/public/applications")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "application-catalog", description = "Public application catalog")
public class PublicApplicationCatalogResource {

    @Inject
    ApplicationRepo applicationRepo;

    @GET
    @PermitAll
    @Operation(operationId = "listPublicApplications", summary = "List the public application catalog",
        description = "Applications whose lifecycleState is PREVIEW, ACTIVE or DEPRECATED, with catalog fields only. No authentication.")
    @APIResponse(responseCode = "200", description = "The catalog",
        content = @Content(mediaType = "application/json",
            schema = @Schema(type = SchemaType.ARRAY, implementation = ApplicationCatalogEntry.class)))
    public List<ApplicationCatalogEntry> catalog() {
        return applicationRepo.findCataloguedWithIgnoreRules().stream()
            .filter(ApplicationCatalogEntry::catalogued)
            .sorted(Comparator.comparing(Application::getRefName))
            .map(ApplicationCatalogEntry::of)
            .toList();
    }
}

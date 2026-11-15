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

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * The signed-out application catalog, for a launch pad before sign-in. Unauthenticated through
 * the framework's {@code @PermitAll} mechanism. It returns {@link ApplicationCatalogEntry} rows
 * only (name, version, lifecycle state and the public sign-in route) for ACTIVE and DEPRECATED
 * applications; nothing else on the application record is read out. The registry is read from the
 * system realm; no realm or tenant header applies. Answers are cached in memory for
 * {@link #CACHE_TTL}, so an unauthenticated caller cannot turn every request into a registry read.
 */
@Path("/public/applications")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "application-catalog", description = "Public application catalog")
public class PublicApplicationCatalogResource {

    /** How long one catalog answer is served before the registry is read again. */
    public static final Duration CACHE_TTL = Duration.ofSeconds(60);

    @Inject
    ApplicationRepo applicationRepo;

    /** Monotonic time in nanoseconds; replaced in tests. */
    LongSupplier clock = System::nanoTime;

    private final AtomicReference<Cached> cached = new AtomicReference<>();

    private record Cached(List<ApplicationCatalogEntry> entries, long readAt) {
    }

    @GET
    @PermitAll
    @Operation(operationId = "listPublicApplications", summary = "List the public application catalog",
        description = "Applications whose lifecycleState is ACTIVE or DEPRECATED, with catalog fields only. "
            + "No authentication. Cached for up to 60 seconds.")
    @APIResponse(responseCode = "200", description = "The catalog",
        content = @Content(mediaType = "application/json",
            schema = @Schema(type = SchemaType.ARRAY, implementation = ApplicationCatalogEntry.class)))
    public List<ApplicationCatalogEntry> catalog() {
        long now = clock.getAsLong();
        Cached current = cached.get();
        if (current != null && now - current.readAt() < CACHE_TTL.toNanos()) {
            return current.entries();
        }
        List<ApplicationCatalogEntry> entries = applicationRepo.findCataloguedWithIgnoreRules().stream()
            .filter(ApplicationCatalogEntry::catalogued)
            .sorted(Comparator.comparing(Application::getRefName))
            .map(ApplicationCatalogEntry::of)
            .toList();
        cached.set(new Cached(entries, now));
        return entries;
    }

    /** Drops the cached answer; the next request reads the registry. */
    public void invalidate() {
        cached.set(null);
    }
}

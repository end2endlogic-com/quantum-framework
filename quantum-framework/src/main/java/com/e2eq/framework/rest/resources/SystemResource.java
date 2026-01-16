package com.e2eq.framework.rest.resources;


import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;

import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.rest.models.ComponentVersion;
import com.e2eq.framework.rest.models.DeployedVersion;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import dev.morphia.MorphiaDatastore;

import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Default;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import dev.morphia.mapping.codec.pojo.EntityModel;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.semver4j.Semver;

@Path("/system")
@Tag(name = "System", description = "System operations")
public class SystemResource {
    @Inject
    @Default
    MorphiaDatastore datastore;

    @Inject
    FunctionalDomainRepo fdRepo;

    @Inject
    SecurityUtils securityUtils;


    @GET
    @Path("/mapping")
    @Produces(MediaType.TEXT_PLAIN)
    public Response mapping() {
        MorphiaDatastore ds = datastore;
        var entities = ds.getMapper().getMappedEntities();
        var list = entities.stream()
                    .map(EntityModel::getName).sorted()
                    .collect(Collectors.joining(", "));
            return Response.ok(list).build();
    }

    @GET
    @Path("/quantumVersion")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public Response version() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("quantum-version.properties")) {
            if (input == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Version information not found").build();
            }

            java.util.Properties prop = new java.util.Properties();
            prop.load(input);

            Semver semver = new Semver(prop.getProperty("quantum-version"));
            String buildDateString = prop.getProperty("quantum-build.date");
            Instant instant = Instant.parse(buildDateString);
            Date buildDate = Date.from(instant);
            String buildNumber = prop.getProperty("quantum-build.number");
            // build a json object with the version and build details
            ComponentVersion version = ComponentVersion.builder()
                    .componentName("Quantum")
                    .major(semver.getMajor())
                    .minor(semver.getMinor())
                    .patch(semver.getPatch())
                    .suffix(semver.getPreRelease())
                    .buildNumber(buildNumber)
                    .timestamp(buildDate)
                    .strictVersionString(semver.getVersion())
                    .build();
            DeployedVersion deployed = new DeployedVersion();
            Map<String, ComponentVersion> versions = Map.of("Quantum", version);

            return Response.ok(version).build();
        }
    }


    // New consolidated import endpoint
    @PUT
    @Path("/functional-domains/import")
    @Consumes({"text/plain", "application/yaml", "text/yaml"})
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"admin", "system"})
    public Response importFunctionalDomains(
            @QueryParam("mode") @DefaultValue("merge") String mode,
            @QueryParam("pruneMissing") @DefaultValue("false") boolean pruneMissing,
            @QueryParam("dryRun") @DefaultValue("false") boolean dryRun,
            String yaml
    ) throws IOException {
        if (yaml == null || yaml.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "YAML payload is required in the request body"))
                    .build();
        }
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        CollectionType listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, FunctionalDomain.class);
        List<FunctionalDomain> incoming;
        try {
            incoming = mapper.readValue(yaml, listType);
        } catch (Exception ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Malformed YAML", "details", ex.getMessage()))
                    .build();
        }
        Map<String, Object> result = applyFunctionalDomainImport(incoming, mode, pruneMissing, dryRun);
        return Response.ok(result).build();
    }

    // Core import logic used by both endpoints
    private Map<String, Object> applyFunctionalDomainImport(List<FunctionalDomain> payload,
                                                            String mode,
                                                            boolean pruneMissing,
                                                            boolean dryRun) {
        if (payload == null) payload = Collections.emptyList();
        com.e2eq.framework.model.persistent.base.DataDomain dataDomain = securityUtils.getSystemDataDomain();

        // Normalize and index incoming by refName (required)
        Map<String, FunctionalDomain> desiredByRef = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (FunctionalDomain f : payload) {
            if (f.getRefName() == null || f.getRefName().isBlank()) {
                warnings.add("Skipping item with missing refName");
                continue;
            }
            // Ensure area present
            if (f.getArea() == null || f.getArea().isBlank()) {
                warnings.add("Item " + f.getRefName() + " has blank area; defaulting to SECURITY");
                f.setArea("SECURITY");
            }
            // Normalize actions: de-duplicate by refName and sort
            f.setFunctionalActions(normalizeActions(f.getFunctionalActions()));
            f.setDataDomain(dataDomain);
            desiredByRef.putIfAbsent(f.getRefName(), f); // keep first occurrence
        }

        // Load existing catalog for this realm (scoped to repository's current realm)
        List<FunctionalDomain> existingAll = fdRepo.getAllList();
        // For upserts we'll just findByRefName which is unique by domain context

        int created = 0, updated = 0, unchanged = 0, deleted = 0;

        if ("replace".equalsIgnoreCase(mode) && pruneMissing) {
            Set<String> desiredKeys = desiredByRef.keySet();
            for (FunctionalDomain ex : existingAll) {
                if (ex == null) continue;
                if (ex.getRefName() == null) continue;
                if (!desiredKeys.contains(ex.getRefName())) {
                    if (!dryRun) {
                        try { fdRepo.delete(ex); } catch (Exception e) { warnings.add("Failed to delete " + ex.getRefName() + ": " + e.getMessage()); }
                    }
                    deleted++;
                }
            }
        }

        for (FunctionalDomain f : desiredByRef.values()) {
            f.setDataDomain(securityUtils.getSystemDataDomain());
            Optional<FunctionalDomain> existingOpt = fdRepo.findByRefName(f.getRefName());
            if (existingOpt.isEmpty()) {
                if (!dryRun) fdRepo.save(f);
                created++;
            } else {
                FunctionalDomain ex = existingOpt.get();
                boolean changed = false;
                if (!Objects.equals(ex.getArea(), f.getArea())) { ex.setArea(f.getArea()); changed = true; }
                if (!Objects.equals(ex.getDisplayName(), f.getDisplayName())) { ex.setDisplayName(f.getDisplayName()); changed = true; }
                // Merge/replace actions
                List<FunctionalAction> merged;
                if ("replace".equalsIgnoreCase(mode)) {
                    merged = normalizeActions(f.getFunctionalActions());
                } else {
                    merged = mergeActions(ex.getFunctionalActions(), f.getFunctionalActions());
                }
                if (!Objects.equals(ex.getFunctionalActions(), merged)) { ex.setFunctionalActions(merged); changed = true; }
                if (changed) {
                    if (!dryRun) fdRepo.save(ex);
                    updated++;
                } else {
                    unchanged++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("updated", updated);
        result.put("unchanged", unchanged);
        result.put("deleted", deleted);
        result.put("totalIncoming", desiredByRef.size());
        if (!warnings.isEmpty()) result.put("warnings", warnings);
        return result;
    }

    private static List<FunctionalAction> normalizeActions(List<FunctionalAction> actions) {
        if (actions == null) return Collections.emptyList();
        Map<String, FunctionalAction> byRef = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (FunctionalAction a : actions) {
            if (a == null) continue;
            String ref = a.getRefName();
            if (ref == null || ref.isBlank()) continue;
            // prefer first occurrence; if later occurrence includes tags/displayName, merge conservatively
            byRef.merge(ref, a, (oldV, newV) -> mergeAction(oldV, newV));
        }
        return new ArrayList<>(byRef.values());
    }

    private static FunctionalAction mergeAction(FunctionalAction a, FunctionalAction b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.getDisplayName() == null && b.getDisplayName() != null) a.setDisplayName(b.getDisplayName());
        // merge tags uniquely preserving order
        List<String> tags = new ArrayList<>();
        if (a.getTags() != null) tags.addAll(a.getTags());
        if (b.getTags() != null) {
            for (String t : b.getTags()) if (t != null && !tags.contains(t)) tags.add(t);
        }
        a.setTags(tags.isEmpty() ? null : tags);
        return a;
    }

    private static List<FunctionalAction> mergeActions(List<FunctionalAction> existing, List<FunctionalAction> incoming) {
        Map<String, FunctionalAction> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (existing != null) {
            for (FunctionalAction a : existing) if (a != null && a.getRefName() != null) map.put(a.getRefName(), a);
        }
        if (incoming != null) {
            for (FunctionalAction a : incoming) if (a != null && a.getRefName() != null) {
                map.merge(a.getRefName(), a, SystemResource::mergeAction);
            }
        }
        return new ArrayList<>(map.values());
    }

}

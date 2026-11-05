package com.e2eq.framework.rest.resources;

import com.e2eq.framework.service.contract.CanonicalOperationHash;
import com.e2eq.framework.service.contract.CanonicalSpecHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Serves this application's contract identity for generated-SDK drift checks.
 *
 * <p>Generated clients embed {@code SPEC_SHA256} (whole document, diagnostic)
 * and per-operation wire hashes. Login compatibility uses
 * {@code operations["POST /security/login"].sha256}, not the document hash.
 *
 * <p>{@code quantum.contract.openapi-location} names the authoritative
 * checked-in contract document (classpath resource, or a filesystem path for
 * dev), the SAME document SDK generation consumes — e.g.
 * {@code contracts/quantum-auth-service.openapi.yaml} packaged as a resource.
 * Unset → 404 (this service declares no generated contract). Configured but
 * unreadable or unhashable → 500, never a silent fallback.
 */
@Path("/contract-hash")
@ApplicationScoped
public class ContractHashResource {

    @ConfigProperty(name = "quantum.contract.openapi-location")
    Optional<String> openapiLocation;

    @Inject ContractDiscoveryConfig discovery;
    @Context UriInfo uriInfo;
    private final ConcurrentMap<String, Map<String, Object>> cached = new ConcurrentHashMap<>();

    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Parameter(name = "version", in = ParameterIn.QUERY, required = false,
            description = "Select an explicitly declared producer contract version; an unknown version is never substituted.",
            schema = @Schema(type = SchemaType.STRING))
    @APIResponse(responseCode = "200", description = "Identity of the selected producer contract snapshot.",
            content = @Content(schema = @Schema(implementation = ContractIdentityResponse.class)))
    @APIResponse(responseCode = "400", description = "Empty or invalid version selector.")
    @APIResponse(responseCode = "404", description = "No declared contract or requested snapshot.")
    public Response get() {
        return getForVersion(uriInfo == null ? null : uriInfo.getQueryParameters().getFirst("version"));
    }

    Response getForVersion(String requestedVersion) {
        if (requestedVersion != null && (requestedVersion.isBlank() || requestedVersion.length() > 128
                || !requestedVersion.equals(requestedVersion.trim()))) {
            return Response.status(400).entity(Map.of("statusMessage", "Invalid contract version selector")).build();
        }
        Map<String, String> snapshots = declaredSnapshots();
        String selected = requestedVersion;
        if (selected == null && discovery != null) selected = discovery.defaultVersion().orElse(null);
        if (selected != null && snapshots.containsKey(selected)) {
            return selectedResponse(selected, snapshots.get(selected));
        }
        if (requestedVersion == null && selected != null) {
            throw new IllegalStateException("Configured default contract snapshot is not declared: " + selected);
        }
        if (openapiLocation.isEmpty() || openapiLocation.get().isBlank()) {
            return Response.status(404).entity(Map.of("statusMessage",
                    "This service does not declare a generated contract (quantum.contract.openapi-location is unset)")).build();
        }
        String location = openapiLocation.get().trim();
        Map<String, Object> current = identity(location);
        if (selected == null || selected.equals(current.get("contract_version"))) return Response.ok(current).build();
        return Response.status(404).entity(Map.of("statusMessage", "No declared contract snapshot for version " + selected)).build();
    }

    private Response selectedResponse(String version, String location) {
        Map<String, Object> body = identity(location);
        if (!version.equals(body.get("contract_version"))) {
            throw new IllegalStateException("Configured contract snapshot version does not match its producer document: " + version);
        }
        return Response.ok(body).build();
    }

    private Map<String, Object> identity(String location) {
        return cached.computeIfAbsent(location, this::computeContractIdentity);
    }

    private Map<String, String> declaredSnapshots() {
        Map<String, String> declared = new LinkedHashMap<>();
        if (discovery == null) return declared;
        for (var entry : discovery.snapshots().values()) {
            if (entry.location().isEmpty() || entry.location().get().isBlank()) continue;
            String version = entry.version();
            if (version.isBlank() || !version.equals(version.trim())
                    || declared.putIfAbsent(version, entry.location().get().trim()) != null) {
                throw new IllegalStateException("Duplicate or invalid declared contract snapshot version");
            }
        }
        return declared;
    }

    private Map<String, Object> computeContractIdentity(String location) {
        try {
            JsonNode spec = readSpec(location);
            String hash = CanonicalSpecHash.sha256(spec);
            JsonNode version = spec.path("info").path("version");
            if (!version.isTextual() || version.textValue().isBlank()) {
                throw new IllegalStateException(
                    "Contract document has no info.version (required for the semver compatibility handshake): " + location);
            }
            Map<String, Map<String, String>> operations = CanonicalOperationHash.sha256ByOperation(spec);
            Log.infof("Contract identity: location=%s algorithm=%s spec_sha256=%s contract_version=%s operations=%d",
                location, CanonicalSpecHash.ALGORITHM, hash, version.textValue(), operations.size());
            return Map.of(
                "spec_sha256", hash,
                "algorithm", CanonicalSpecHash.ALGORITHM,
                "contract_version", version.textValue(),
                "source", location,
                "operation_algorithm", CanonicalOperationHash.ALGORITHM,
                "operations", operations);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                "Unable to compute contract hash from configured quantum.contract.openapi-location=" + location, e);
        }
    }

    private JsonNode readSpec(String location) throws IOException {
        byte[] raw;
        java.nio.file.Path file = java.nio.file.Path.of(location);
        if (Files.isRegularFile(file)) {
            raw = Files.readAllBytes(file);
        } else {
            try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(location)) {
                if (stream == null) {
                    throw new IOException("Contract document not found on filesystem or classpath: " + location);
                }
                raw = stream.readAllBytes();
            }
        }
        ObjectMapper mapper = location.endsWith(".json")
            ? new ObjectMapper()
            : new ObjectMapper(new YAMLFactory());
        return mapper.readTree(raw);
    }
}

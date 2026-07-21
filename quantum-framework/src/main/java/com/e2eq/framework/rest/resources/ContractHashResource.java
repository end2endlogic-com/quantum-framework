package com.e2eq.framework.rest.resources;

import com.e2eq.framework.service.contract.CanonicalSpecHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.logging.Log;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * Serves this application's contract identity for generated-SDK drift checks.
 *
 * <p>Generated clients embed the canonical hash of the OpenAPI document they
 * were generated from ({@code GeneratedContract.SPEC_SHA256}); at handshake
 * they compare it against this endpoint and fail fast on mismatch — the
 * generated code is out of date and must be regenerated.
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

    private volatile Map<String, String> cached;

    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        if (openapiLocation.isEmpty() || openapiLocation.get().isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of(
                    "statusMessage",
                    "This service does not declare a generated contract (quantum.contract.openapi-location is unset)"))
                .build();
        }
        Map<String, String> body = cached;
        if (body == null) {
            body = computeContractIdentity(openapiLocation.get().trim());
            cached = body;
        }
        return Response.ok(body).build();
    }

    private Map<String, String> computeContractIdentity(String location) {
        try {
            JsonNode spec = readSpec(location);
            String hash = CanonicalSpecHash.sha256(spec);
            JsonNode version = spec.path("info").path("version");
            if (!version.isTextual() || version.textValue().isBlank()) {
                throw new IllegalStateException(
                    "Contract document has no info.version (required for the semver compatibility handshake): " + location);
            }
            Log.infof("Contract identity: location=%s algorithm=%s spec_sha256=%s contract_version=%s",
                location, CanonicalSpecHash.ALGORITHM, hash, version.textValue());
            return Map.of(
                "spec_sha256", hash,
                "algorithm", CanonicalSpecHash.ALGORITHM,
                "contract_version", version.textValue(),
                "source", location);
        } catch (IOException | RuntimeException e) {
            // Configured but unusable is a deployment defect; surface it, don't mask it.
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

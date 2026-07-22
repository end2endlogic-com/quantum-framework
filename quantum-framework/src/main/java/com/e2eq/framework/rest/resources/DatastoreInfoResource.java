package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.mongodb.ConnectionString;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reports which MongoDB deployment THIS service instance is connected to.
 *
 * <p>Datastore attachment is a property of the running service instance, not
 * of a deployment environment: two services in the same environment can point
 * at different clusters, and the system console's topology view must render
 * what each instance actually uses rather than an environment-level guess.
 *
 * <p>The connection string is parsed and projected — hosts, SRV, replica set,
 * TLS, default database. Credentials and the raw URI are never returned.
 */
@Path("/system/datastore-info")
@ApplicationScoped
@Authenticated
@FunctionalMapping(area = "system", domain = "service_instance")
public class DatastoreInfoResource {

    @ConfigProperty(name = "quarkus.mongodb.connection-string")
    Optional<String> connectionString;

    @GET
    @FunctionalAction("view")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        if (connectionString.isEmpty() || connectionString.get().isBlank()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("statusMessage",
                    "This service does not declare a MongoDB connection (quarkus.mongodb.connection-string is unset)"))
                .build();
        }
        ConnectionString parsed;
        try {
            parsed = new ConnectionString(connectionString.get().trim());
        }
        catch (RuntimeException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("statusMessage",
                    "The configured MongoDB connection string could not be parsed: " + e.getMessage()))
                .build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        List<String> hosts = parsed.getHosts();
        body.put("hosts", hosts);
        body.put("srv", parsed.isSrvProtocol());
        // A stable one-line label for topology displays.
        body.put("clusterLabel", (parsed.isSrvProtocol() ? "srv://" : "") + String.join(",", hosts)
            + (parsed.getRequiredReplicaSetName() != null ? " (rs: " + parsed.getRequiredReplicaSetName() + ")" : ""));
        if (parsed.getRequiredReplicaSetName() != null) body.put("replicaSet", parsed.getRequiredReplicaSetName());
        if (parsed.getSslEnabled() != null) body.put("tls", parsed.getSslEnabled());
        if (parsed.getDatabase() != null) body.put("defaultDatabase", parsed.getDatabase());
        return Response.ok(body).build();
    }
}

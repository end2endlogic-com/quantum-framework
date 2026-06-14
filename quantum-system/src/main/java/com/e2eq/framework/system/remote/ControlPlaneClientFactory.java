package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.util.Optional;

/**
 * Builds the control-plane client (the SDK-generated JAX-RS interface
 * {@link DefaultEndpoint}) via MicroProfile Rest Client — the transport is
 * provided by the runtime, no hand-written HTTP. Used by the in-app producer /
 * membership service; tests inject a {@link DefaultEndpoint} stub directly.
 */
public final class ControlPlaneClientFactory {

    private ControlPlaneClientFactory() {
    }

    public static DefaultEndpoint build(String baseUrl, Optional<String> bearerToken) {
        RestClientBuilder builder = RestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl.replaceAll("/+$", "")));
        bearerToken.filter(t -> !t.isBlank()).ifPresent(token ->
            builder.register((ClientRequestFilter) ctx ->
                ctx.getHeaders().putSingle("Authorization", "Bearer " + token)));
        return builder.build(DefaultEndpoint.class);
    }
}

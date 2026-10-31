package com.e2eq.framework.rest.media;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A short-lived read capability returned to an authorized caller. */
public record MediaAccessGrant(
        URI accessUri,
        Instant expiresAt,
        Map<String, String> responseHeaders) {

    public MediaAccessGrant {
        Objects.requireNonNull(accessUri, "accessUri is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
    }
}

package com.e2eq.framework.rest.media;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A short-lived upload capability returned to an authorized caller. */
public record MediaUploadGrant(
        URI uploadUri,
        Instant expiresAt,
        Map<String, String> requiredHeaders) {

    public MediaUploadGrant {
        Objects.requireNonNull(uploadUri, "uploadUri is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
    }
}

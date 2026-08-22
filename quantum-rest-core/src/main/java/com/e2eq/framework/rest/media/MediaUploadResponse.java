package com.e2eq.framework.rest.media;

import com.e2eq.framework.model.persistent.collaboration.MediaReference;

/** Persisted media metadata paired with a response-only direct upload grant. */
public record MediaUploadResponse(
        MediaReference mediaReference,
        MediaUploadGrant uploadGrant) {
}

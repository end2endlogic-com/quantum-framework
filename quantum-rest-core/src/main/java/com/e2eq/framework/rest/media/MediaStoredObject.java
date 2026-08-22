package com.e2eq.framework.rest.media;

import com.e2eq.framework.model.persistent.collaboration.MediaReference;

/** Trusted object metadata returned by a storage provider after upload verification. */
public record MediaStoredObject(
        long contentLength,
        String contentType,
        String sha256,
        MediaReference.ScanStatus scanStatus) {
}

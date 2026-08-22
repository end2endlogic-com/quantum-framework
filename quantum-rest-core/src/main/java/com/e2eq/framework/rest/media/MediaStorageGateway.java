package com.e2eq.framework.rest.media;

import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import java.time.Duration;

/**
 * Application-provided media storage seam. Implementations create short-lived
 * access grants; callers must never persist a grant URI on MediaReference.
 */
public interface MediaStorageGateway {

    /** Stable provider identifier persisted on MediaReference. */
    String providerId();

    /** Default private container used for newly prepared uploads. */
    String defaultContainer();

    default boolean supports(MediaReference mediaReference) {
        return mediaReference != null
                && providerId().equals(mediaReference.getStorageProvider());
    }

    MediaUploadGrant prepareUpload(MediaReference mediaReference, Duration lifetime);

    /** Verify the uploaded object using provider-owned metadata, never caller claims. */
    MediaStoredObject verifyUpload(MediaReference mediaReference);

    MediaAccessGrant prepareDownload(
            MediaReference mediaReference,
            String contentDisposition,
            Duration lifetime);

    void delete(MediaReference mediaReference);
}

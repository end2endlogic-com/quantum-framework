package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Objects;

@ApplicationScoped
public class MediaReferenceRepo extends MorphiaRepo<MediaReference> {

    @Override
    public MediaReference save(@Valid MediaReference value) {
        String actorId = value.getCreatedBy() == null ? null : value.getCreatedBy().getActorId();
        if (actorId == null || actorId.isBlank()) {
            throw new MediaReferenceOperationException(
                    MediaReferenceOperationException.Code.CREATOR_REQUIRED,
                    "A server-derived media creator is required");
        }
        Instant now = Instant.now();
        MediaReference existing = value.getId() == null ? null : findById(value.getId()).orElse(null);
        if (existing == null) {
            value.setCreatedAt(now);
        } else {
            String existingActorId = existing.getCreatedBy() == null
                    ? null
                    : existing.getCreatedBy().getActorId();
            if (!Objects.equals(existingActorId, actorId)) {
                throw new MediaReferenceOperationException(
                        MediaReferenceOperationException.Code.IMMUTABLE_CREATOR,
                        "A persisted media creator cannot be changed");
            }
            if (!sameStorageIdentity(existing, value)) {
                throw new MediaReferenceOperationException(
                        MediaReferenceOperationException.Code.IMMUTABLE_STORAGE_IDENTITY,
                        "A persisted media storage identity cannot be changed");
            }
            value.setCreatedAt(existing.getCreatedAt());
        }
        value.setLastUpdatedAt(now);
        return super.save(value);
    }

    private static boolean sameStorageIdentity(MediaReference left, MediaReference right) {
        return Objects.equals(left.getStorageProvider(), right.getStorageProvider())
                && Objects.equals(left.getStorageContainer(), right.getStorageContainer())
                && Objects.equals(left.getObjectKey(), right.getObjectKey());
    }
}

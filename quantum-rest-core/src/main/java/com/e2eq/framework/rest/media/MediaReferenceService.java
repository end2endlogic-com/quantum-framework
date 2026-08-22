package com.e2eq.framework.rest.media;

import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.collaboration.MediaReference;
import com.e2eq.framework.model.persistent.morphia.MediaReferenceRepo;
import com.e2eq.framework.rest.models.MediaUploadRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class MediaReferenceService {

    @Inject
    MediaReferenceRepo mediaReferenceRepo;

    @Inject
    Instance<MediaStorageGateway> storageGateways;

    @ConfigProperty(name = "quantum.media.grant-lifetime", defaultValue = "PT15M")
    Duration grantLifetime;

    @ConfigProperty(name = "quantum.media.max-content-length", defaultValue = "104857600")
    long maxContentLength;

    public MediaUploadResponse prepareUpload(MediaUploadRequest request, ActorReference actor) {
        requireActor(actor);
        if (request == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "Media upload metadata is required");
        }
        if (request.contentLength <= 0 || request.contentLength > maxContentLength) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "contentLength must be between 1 and " + maxContentLength + " bytes");
        }
        MediaStorageGateway gateway = defaultGateway();
        String identity = UUID.randomUUID().toString();
        MediaReference mediaReference = MediaReference.builder()
                .refName("media-" + identity)
                .displayName(requiredRequest(request.displayFileName, "displayFileName is required"))
                .storageProvider(required(gateway.providerId(), "Storage provider id is required"))
                .storageContainer(required(gateway.defaultContainer(), "Storage container is required"))
                .objectKey("media/" + identity)
                .displayFileName(requiredRequest(request.displayFileName, "displayFileName is required"))
                .contentType(requiredRequest(request.contentType, "contentType is required"))
                .contentLength(request.contentLength)
                .sha256(clean(request.sha256))
                .purpose(clean(request.purpose))
                .classification(request.classification == null
                        ? MediaReference.Classification.INTERNAL
                        : request.classification)
                .status(MediaReference.Status.PENDING_UPLOAD)
                .scanStatus(MediaReference.ScanStatus.NOT_SCANNED)
                .createdBy(actor)
                .metadata(request.metadata)
                .build();
        MediaReference saved = mediaReferenceRepo.save(mediaReference);
        MediaUploadGrant grant = gateway.prepareUpload(saved, grantLifetime);
        if (grant == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.SIGNING_FAILED,
                    "Media storage gateway returned no upload grant");
        }
        return new MediaUploadResponse(saved, grant);
    }

    public MediaReference get(String mediaReferenceId) {
        ObjectId id = objectId(mediaReferenceId);
        return mediaReferenceRepo.findById(id).orElseThrow(() ->
                new MediaStorageException(
                        MediaStorageException.Code.OBJECT_NOT_FOUND,
                        "MediaReference not found: " + mediaReferenceId));
    }

    public MediaReference completeUpload(String mediaReferenceId) {
        MediaReference mediaReference = get(mediaReferenceId);
        if (mediaReference.getStatus() != MediaReference.Status.PENDING_UPLOAD
                && mediaReference.getStatus() != MediaReference.Status.UPLOADED) {
            return mediaReference;
        }
        MediaStoredObject stored = gatewayFor(mediaReference).verifyUpload(mediaReference);
        if (stored == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.STORAGE_UNAVAILABLE,
                    "Media storage gateway returned no verified object metadata");
        }
        if (stored.contentLength() != mediaReference.getContentLength()) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "Uploaded object length does not match the prepared MediaReference");
        }
        if (!requiredRequest(stored.contentType(), "Stored object content type is required")
                .equalsIgnoreCase(mediaReference.getContentType())) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "Uploaded object content type does not match the prepared MediaReference");
        }
        String verifiedSha256 = clean(stored.sha256());
        String expectedSha256 = clean(mediaReference.getSha256());
        if (expectedSha256 != null
                && (verifiedSha256 == null || !expectedSha256.equalsIgnoreCase(verifiedSha256))) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "Uploaded object checksum does not match the prepared MediaReference");
        }
        if (expectedSha256 == null && verifiedSha256 != null) {
            mediaReference.setSha256(verifiedSha256);
        }
        MediaReference.ScanStatus scanStatus = stored.scanStatus() == null
                ? MediaReference.ScanStatus.NOT_SCANNED
                : stored.scanStatus();
        mediaReference.setScanStatus(scanStatus);
        mediaReference.setStatus(statusFor(scanStatus));
        return mediaReferenceRepo.save(mediaReference);
    }

    public MediaAccessGrant prepareDownload(
            String mediaReferenceId,
            String contentDisposition) {
        MediaReference mediaReference = get(mediaReferenceId);
        if (mediaReference.getStatus() != MediaReference.Status.AVAILABLE) {
            throw new MediaStorageException(
                    MediaStorageException.Code.ACCESS_DENIED,
                    "MediaReference is not available for download");
        }
        String disposition = clean(contentDisposition);
        if (disposition == null) {
            disposition = "attachment";
        }
        if (!disposition.equals("inline") && !disposition.equals("attachment")) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "contentDisposition must be inline or attachment");
        }
        MediaAccessGrant grant = gatewayFor(mediaReference).prepareDownload(
                mediaReference,
                disposition,
                grantLifetime);
        if (grant == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.SIGNING_FAILED,
                    "Media storage gateway returned no download grant");
        }
        return grant;
    }

    private MediaStorageGateway defaultGateway() {
        List<MediaStorageGateway> gateways = storageGateways.stream().limit(2).toList();
        if (gateways.size() != 1) {
            throw new MediaStorageException(
                    MediaStorageException.Code.STORAGE_UNAVAILABLE,
                    "Exactly one default MediaStorageGateway must be configured");
        }
        return gateways.get(0);
    }

    private MediaStorageGateway gatewayFor(MediaReference mediaReference) {
        List<MediaStorageGateway> matches = storageGateways.stream()
                .filter(gateway -> gateway.supports(mediaReference))
                .limit(2)
                .toList();
        if (matches.size() != 1) {
            throw new MediaStorageException(
                    MediaStorageException.Code.STORAGE_UNAVAILABLE,
                    "No unique MediaStorageGateway is configured for provider "
                            + mediaReference.getStorageProvider());
        }
        return matches.get(0);
    }

    private static void requireActor(ActorReference actor) {
        if (actor == null || clean(actor.getActorId()) == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.ACCESS_DENIED,
                    "An authenticated actor is required");
        }
    }

    static MediaReference.Status statusFor(MediaReference.ScanStatus scanStatus) {
        return switch (scanStatus) {
            case CLEAN -> MediaReference.Status.AVAILABLE;
            case PENDING -> MediaReference.Status.SCANNING;
            case INFECTED -> MediaReference.Status.QUARANTINED;
            case FAILED -> MediaReference.Status.FAILED;
            case NOT_SCANNED -> MediaReference.Status.UPLOADED;
        };
    }

    private static ObjectId objectId(String value) {
        try {
            return new ObjectId(value);
        } catch (RuntimeException failure) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    "mediaReferenceId must be a Mongo ObjectId");
        }
    }

    private static String required(String value, String message) {
        String clean = clean(value);
        if (clean == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.STORAGE_UNAVAILABLE,
                    message);
        }
        return clean;
    }

    private static String requiredRequest(String value, String message) {
        String clean = clean(value);
        if (clean == null) {
            throw new MediaStorageException(
                    MediaStorageException.Code.INVALID_REFERENCE,
                    message);
        }
        return clean;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
}

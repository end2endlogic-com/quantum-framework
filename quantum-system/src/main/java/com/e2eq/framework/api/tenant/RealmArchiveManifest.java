package com.e2eq.framework.api.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Control-plane contract describing a verified realm archive.
 *
 * <p>The framework owns this provider-neutral contract; enterprise modules
 * supply the object-storage and database implementation.</p>
 */
public final class RealmArchiveManifest {
    private final String archiveRef;
    private final String realmId;
    private final String databaseName;
    private final String applicationId;
    private final String tenantId;
    private final String objectUri;
    private final String format;
    private final String checksumAlgorithm;
    private final String checksum;
    private final String encryptionKeyRef;
    private final long sizeBytes;
    private final boolean objectVerified;
    private final boolean restoreRehearsalVerified;
    private final Instant createdAt;
    private final Instant verifiedAt;
    private final List<RealmArchiveCollectionManifest> collections;

    public RealmArchiveManifest(
        String archiveRef,
        String realmId,
        String databaseName,
        String applicationId,
        String tenantId,
        String objectUri,
        String format,
        String checksumAlgorithm,
        String checksum,
        String encryptionKeyRef,
        long sizeBytes,
        boolean objectVerified,
        boolean restoreRehearsalVerified,
        Instant createdAt,
        Instant verifiedAt,
        List<RealmArchiveCollectionManifest> collections
    ) {
        this.archiveRef = required(archiveRef, "archiveRef");
        this.realmId = required(realmId, "realmId");
        this.databaseName = required(databaseName, "databaseName");
        this.applicationId = required(applicationId, "applicationId");
        this.tenantId = tenantId;
        this.objectUri = required(objectUri, "objectUri");
        this.format = required(format, "format");
        this.checksumAlgorithm = required(checksumAlgorithm, "checksumAlgorithm");
        this.checksum = required(checksum, "checksum");
        this.encryptionKeyRef = required(encryptionKeyRef, "encryptionKeyRef");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes cannot be negative");
        }
        this.sizeBytes = sizeBytes;
        this.objectVerified = objectVerified;
        this.restoreRehearsalVerified = restoreRehearsalVerified;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt cannot be null");
        this.collections = collections == null ? List.of() : List.copyOf(collections);
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    public String archiveRef() { return archiveRef; }
    public String realmId() { return realmId; }
    public String databaseName() { return databaseName; }
    public String applicationId() { return applicationId; }
    public String tenantId() { return tenantId; }
    public String objectUri() { return objectUri; }
    public String format() { return format; }
    public String checksumAlgorithm() { return checksumAlgorithm; }
    public String checksum() { return checksum; }
    public String encryptionKeyRef() { return encryptionKeyRef; }
    public long sizeBytes() { return sizeBytes; }
    public boolean objectVerified() { return objectVerified; }
    public boolean restoreRehearsalVerified() { return restoreRehearsalVerified; }
    public Instant createdAt() { return createdAt; }
    public Instant verifiedAt() { return verifiedAt; }
    public List<RealmArchiveCollectionManifest> collections() { return collections; }

    public String getArchiveRef() { return archiveRef; }
    public String getRealmId() { return realmId; }
    public String getDatabaseName() { return databaseName; }
    public String getApplicationId() { return applicationId; }
    public String getTenantId() { return tenantId; }
    public String getObjectUri() { return objectUri; }
    public String getFormat() { return format; }
    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public String getChecksum() { return checksum; }
    public String getEncryptionKeyRef() { return encryptionKeyRef; }
    public long getSizeBytes() { return sizeBytes; }
    public boolean isObjectVerified() { return objectVerified; }
    public boolean isRestoreRehearsalVerified() { return restoreRehearsalVerified; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public List<RealmArchiveCollectionManifest> getCollections() { return collections; }
}

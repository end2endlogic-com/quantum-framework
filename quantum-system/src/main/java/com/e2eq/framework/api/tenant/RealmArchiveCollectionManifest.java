package com.e2eq.framework.api.tenant;

import java.util.Objects;

/** Verified collection inventory captured in a realm archive manifest. */
public final class RealmArchiveCollectionManifest {
    private final String collectionName;
    private final long documentCount;
    private final int indexCount;

    public RealmArchiveCollectionManifest(String collectionName, long documentCount, int indexCount) {
        this.collectionName = Objects.requireNonNull(collectionName, "collectionName cannot be null");
        if (collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName cannot be blank");
        }
        if (documentCount < 0) {
            throw new IllegalArgumentException("documentCount cannot be negative");
        }
        if (indexCount < 0) {
            throw new IllegalArgumentException("indexCount cannot be negative");
        }
        this.documentCount = documentCount;
        this.indexCount = indexCount;
    }

    public String collectionName() { return collectionName; }
    public long documentCount() { return documentCount; }
    public int indexCount() { return indexCount; }

    public String getCollectionName() { return collectionName; }
    public long getDocumentCount() { return documentCount; }
    public int getIndexCount() { return indexCount; }
}

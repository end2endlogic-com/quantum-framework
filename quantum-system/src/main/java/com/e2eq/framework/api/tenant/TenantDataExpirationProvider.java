package com.e2eq.framework.api.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral boundary for scheduling tenant-scoped MongoDB rows for TTL
 * cleanup.
 *
 * <p>Implementations must use the same {@code purgeBatchRef} and
 * {@code purgeAfter} for every matched row, operate only on collections
 * declared tenant-scoped, and reconcile matched and stamped counts before
 * returning.</p>
 */
public interface TenantDataExpirationProvider {

    ExpirationManifest stampAndVerify(ExpirationRequest request);

    ExpirationManifest inspect(String purgeBatchRef);

    record ExpirationRequest(
        String executionRef,
        String realmId,
        String databaseName,
        String applicationId,
        TenantDataScope tenantScope,
        String purgeBatchRef,
        Instant purgeAfter
    ) {
        public ExpirationRequest {
            executionRef = required(executionRef, "executionRef");
            realmId = required(realmId, "realmId");
            databaseName = required(databaseName, "databaseName");
            applicationId = required(applicationId, "applicationId");
            tenantScope = Objects.requireNonNull(tenantScope, "tenantScope cannot be null");
            purgeBatchRef = required(purgeBatchRef, "purgeBatchRef");
            purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null");
        }
    }

    record CollectionExpiration(
        String collectionName,
        long matchedDocumentCount,
        long stampedDocumentCount
    ) {
        public CollectionExpiration {
            collectionName = required(collectionName, "collectionName");
            if (matchedDocumentCount < 0 || stampedDocumentCount < 0) {
                throw new IllegalArgumentException("document counts cannot be negative");
            }
            if (stampedDocumentCount > matchedDocumentCount) {
                throw new IllegalArgumentException(
                    "stampedDocumentCount cannot exceed matchedDocumentCount");
            }
        }

        public boolean coverageVerified() {
            return matchedDocumentCount == stampedDocumentCount;
        }
    }

    record ExpirationManifest(
        String executionRef,
        String realmId,
        String tenantId,
        String purgeBatchRef,
        Instant purgeAfter,
        List<CollectionExpiration> collections
    ) {
        public ExpirationManifest {
            executionRef = required(executionRef, "executionRef");
            realmId = required(realmId, "realmId");
            tenantId = required(tenantId, "tenantId");
            purgeBatchRef = required(purgeBatchRef, "purgeBatchRef");
            purgeAfter = Objects.requireNonNull(purgeAfter, "purgeAfter cannot be null");
            collections = collections == null ? List.of() : List.copyOf(collections);
        }

        public boolean coverageVerified() {
            return !collections.isEmpty()
                && collections.stream().allMatch(CollectionExpiration::coverageVerified);
        }

        public long matchedDocumentCount() {
            return collections.stream().mapToLong(CollectionExpiration::matchedDocumentCount).sum();
        }

        public long stampedDocumentCount() {
            return collections.stream().mapToLong(CollectionExpiration::stampedDocumentCount).sum();
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}

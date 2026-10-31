package com.e2eq.framework.api.tenant;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles the database's persisted collections with the collection
 * ownership catalog before a pooled tenant purge can be planned.
 *
 * <p>The provider supplies user collection names after excluding MongoDB
 * internal namespaces. Every supplied collection must have exactly one
 * metadata declaration. Extra declarations are allowed because a mapped
 * collection may not have been created yet.</p>
 */
public record TenantCollectionInventory(
    String realmId,
    String databaseName,
    Set<String> persistedCollectionNames,
    List<TenantCollectionMetadata> declaredCollections
) {
    public TenantCollectionInventory {
        realmId = required(realmId, "realmId");
        databaseName = required(databaseName, "databaseName");
        persistedCollectionNames = persistedCollectionNames == null
            ? Set.of()
            : Set.copyOf(persistedCollectionNames);
        declaredCollections = declaredCollections == null
            ? List.of()
            : List.copyOf(declaredCollections);

        Set<String> declarationNames = new HashSet<>();
        for (TenantCollectionMetadata declaration : declaredCollections) {
            Objects.requireNonNull(declaration, "declaredCollections cannot contain null");
            if (!declarationNames.add(declaration.collectionName())) {
                throw new IllegalArgumentException(
                    "duplicate collection declaration: " + declaration.collectionName());
            }
        }

        Set<String> unclassified = new HashSet<>(persistedCollectionNames);
        unclassified.removeAll(declarationNames);
        if (!unclassified.isEmpty()) {
            throw new IllegalArgumentException(
                "persisted collections are missing storage-scope declarations: " + unclassified);
        }
    }

    /**
     * Returns only existing collections that are explicitly safe for
     * tenant-scoped selection and expiration.
     */
    public List<TenantCollectionMetadata> tenantPurgeCollections() {
        return declaredCollections.stream()
            .filter(metadata -> persistedCollectionNames.contains(metadata.collectionName()))
            .filter(metadata ->
                metadata.storageScope() == TenantCollectionStorageScope.TENANT_SCOPED)
            .collect(Collectors.toUnmodifiableList());
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }
}

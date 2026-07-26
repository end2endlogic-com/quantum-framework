package com.e2eq.framework.system.tenant;

import com.e2eq.framework.api.tenant.TenantCollectionInventory;
import com.e2eq.framework.api.tenant.TenantCollectionMetadata;
import com.e2eq.framework.api.tenant.TenantCollectionRegistry;
import com.mongodb.client.MongoClient;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Default MongoDB collection ownership registry.
 *
 * <p>The registry reads the database inventory directly from MongoDB so a
 * decommission check cannot create a Morphia datastore, collections, or
 * indexes as a side effect. Applications may replace this default bean when
 * collection ownership is supplied by another authoritative catalog.</p>
 */
@ApplicationScoped
@DefaultBean
public class MongoTenantCollectionRegistry implements TenantCollectionRegistry {

    static final TenantCollectionMetadata.TenantFieldMapping DEFAULT_TENANT_FIELDS =
        new TenantCollectionMetadata.TenantFieldMapping(
            "dataDomain.orgRefName",
            "dataDomain.accountNum",
            "dataDomain.tenantId",
            "dataDomain.dataSegment",
            "purgeBatchRef",
            "purgeAfter");

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quantum.tenant-storage.tenant-scoped-collections")
    Optional<String> tenantScopedCollections;

    @ConfigProperty(name = "quantum.tenant-storage.realm-shared-collections")
    Optional<String> realmSharedCollections;

    @ConfigProperty(name = "quantum.tenant-storage.system-global-collections")
    Optional<String> systemGlobalCollections;

    @Override
    public TenantCollectionInventory inventory(String realmId, String databaseName) {
        requireIdentifier(realmId, "realmId");
        requireIdentifier(databaseName, "databaseName");
        Set<String> persistedCollections = mongoClient
            .getDatabase(databaseName)
            .listCollectionNames()
            .into(new LinkedHashSet<>())
            .stream()
            .filter(MongoTenantCollectionRegistry::isUserCollection)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

        return buildInventory(
            realmId,
            databaseName,
            persistedCollections,
            tenantScopedCollections.orElse(""),
            realmSharedCollections.orElse(""),
            systemGlobalCollections.orElse(""));
    }

    static TenantCollectionInventory buildInventory(
        String realmId,
        String databaseName,
        Set<String> persistedCollections,
        String tenantScopedCollections,
        String realmSharedCollections,
        String systemGlobalCollections
    ) {
        Set<String> userCollections = persistedCollections == null
            ? Set.of()
            : persistedCollections.stream()
                .filter(MongoTenantCollectionRegistry::isUserCollection)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<TenantCollectionMetadata> declarations = Stream.of(
                parseCsv(tenantScopedCollections).stream()
                    .map(name -> TenantCollectionMetadata.tenantScoped(
                        name, DEFAULT_TENANT_FIELDS)),
                parseCsv(realmSharedCollections).stream()
                    .map(TenantCollectionMetadata::realmShared),
                parseCsv(systemGlobalCollections).stream()
                    .map(TenantCollectionMetadata::systemGlobal))
            .flatMap(stream -> stream)
            .toList();

        return new TenantCollectionInventory(
            realmId, databaseName, userCollections, declarations);
    }

    private static boolean isUserCollection(String collectionName) {
        return collectionName != null
            && !collectionName.isBlank()
            && !collectionName.startsWith("system.");
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .forEach(values::add);
        return values;
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name + " cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}

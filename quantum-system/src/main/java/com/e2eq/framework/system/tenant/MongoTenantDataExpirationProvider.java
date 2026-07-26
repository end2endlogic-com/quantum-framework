package com.e2eq.framework.system.tenant;

import com.e2eq.framework.api.tenant.TenantCollectionMetadata;
import com.e2eq.framework.api.tenant.TenantCollectionRegistry;
import com.e2eq.framework.api.tenant.TenantDataExpirationProvider;
import com.e2eq.framework.api.tenant.TenantDataScope;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Default MongoDB implementation of tenant-scoped delayed deletion.
 *
 * <p>Access must be revoked before this provider is called. Each declared
 * tenant collection receives an absolute-time TTL index and every matching
 * document receives the same purge batch and timestamp. Verification counts
 * the stamped rows after the update, so retries remain idempotent.</p>
 */
@ApplicationScoped
@DefaultBean
public class MongoTenantDataExpirationProvider implements TenantDataExpirationProvider {

    @Inject
    MongoClient mongoClient;

    @Inject
    TenantCollectionRegistry collectionRegistry;

    @Override
    public ExpirationManifest stampAndVerify(ExpirationRequest request) {
        List<TenantCollectionMetadata> collections = collectionRegistry
            .inventory(request.realmId(), request.databaseName())
            .tenantPurgeCollections();
        if (collections.isEmpty()) {
            throw new IllegalStateException(
                "No persisted tenant-scoped collections are declared for pooled realm "
                    + request.realmId());
        }

        List<CollectionExpiration> results = new ArrayList<>();
        for (TenantCollectionMetadata metadata : collections) {
            TenantCollectionMetadata.TenantFieldMapping fields =
                metadata.tenantFieldMapping();
            MongoCollection<Document> collection = mongoClient
                .getDatabase(request.databaseName())
                .getCollection(metadata.collectionName());

            collection.createIndex(
                Indexes.ascending(fields.purgeAfterPath()),
                new IndexOptions()
                    .name(ttlIndexName(fields.purgeAfterPath()))
                    .expireAfter(0L, TimeUnit.SECONDS));

            Bson tenantFilter = tenantFilter(request.tenantScope(), fields);
            long matched = collection.countDocuments(tenantFilter);
            collection.updateMany(
                tenantFilter,
                Updates.combine(
                    Updates.set(fields.purgeBatchRefPath(), request.purgeBatchRef()),
                    Updates.set(fields.purgeAfterPath(), Date.from(request.purgeAfter()))));

            long stamped = collection.countDocuments(and(
                tenantFilter,
                eq(fields.purgeBatchRefPath(), request.purgeBatchRef()),
                eq(fields.purgeAfterPath(), Date.from(request.purgeAfter()))));
            results.add(new CollectionExpiration(
                metadata.collectionName(), matched, stamped));
        }

        ExpirationManifest manifest = new ExpirationManifest(
            request.executionRef(),
            request.realmId(),
            request.tenantScope().tenantId(),
            request.purgeBatchRef(),
            request.purgeAfter(),
            results);
        if (!manifest.coverageVerified()) {
            throw new IllegalStateException(
                "Tenant expiration coverage did not reconcile for purge batch "
                    + request.purgeBatchRef());
        }
        return manifest;
    }

    static Bson tenantFilter(
        TenantDataScope scope,
        TenantCollectionMetadata.TenantFieldMapping fields
    ) {
        return and(
            eq(fields.orgRefNamePath(), scope.orgRefName()),
            eq(fields.accountNumPath(), scope.accountNum()),
            eq(fields.tenantIdPath(), scope.tenantId()),
            eq(fields.dataSegmentPath(), scope.dataSegment()));
    }

    static String ttlIndexName(String purgeAfterPath) {
        return "quantum_tenant_purge_ttl_"
            + purgeAfterPath.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}

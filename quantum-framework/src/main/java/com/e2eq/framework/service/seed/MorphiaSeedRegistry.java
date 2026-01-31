package com.e2eq.framework.service.seed;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Morphia-based implementation of SeedRegistry that uses SeedRegistryRepository
 * instead of direct MongoDB access. This ensures proper security context, audit trails,
 * and data domain handling.
 */
@ApplicationScoped
public class MorphiaSeedRegistry implements SeedRegistry {

    @Inject
    SeedRegistryRepository registryRepo;

    @Override
    public boolean shouldApply(SeedContext context,
                               SeedPackManifest manifest,
                               SeedPackManifest.Dataset dataset,
                               String checksum) {
        Optional<SeedRegistryEntry> existing = registryRepo.findEntry(
                context.getRealm(),
                manifest.getSeedPack(),
                manifest.getVersion(),
                dataset.getCollection());

        if (existing.isEmpty()) {
            Log.debugf("Seed registry: no existing record for %s %s %s %s - should apply",
                    manifest.getSeedPack(), manifest.getVersion(), context.getRealm(), dataset.getCollection());
            return true;
        }

        boolean shouldApply = !Objects.equals(existing.get().getChecksum(), checksum);
        if (shouldApply) {
            Log.debugf("Seed registry: checksum mismatch (existing: %s, calculated: %s) for %s %s %s %s - should apply",
                    existing.get().getChecksum(), checksum,
                    manifest.getSeedPack(), manifest.getVersion(), context.getRealm(), dataset.getCollection());
        }
        return shouldApply;
    }

    @Override
    public Optional<String> getLastAppliedChecksum(SeedContext context,
                                                   SeedPackManifest manifest,
                                                   SeedPackManifest.Dataset dataset) {
        return registryRepo.findEntry(
                context.getRealm(),
                manifest.getSeedPack(),
                manifest.getVersion(),
                dataset.getCollection())
                .map(SeedRegistryEntry::getChecksum);
    }

    @Override
    @Transactional
    public void recordApplied(SeedContext context,
                             SeedPackManifest manifest,
                             SeedPackManifest.Dataset dataset,
                             String checksum,
                             int recordsApplied) {
        Optional<SeedRegistryEntry> existingOpt = registryRepo.findEntry(
                context.getRealm(),
                manifest.getSeedPack(),
                manifest.getVersion(),
                dataset.getCollection());

        SeedRegistryEntry entry;
        if (existingOpt.isPresent()) {
            entry = existingOpt.get();
        } else {
            entry = new SeedRegistryEntry();
            entry.setRefName(String.format("%s-%s-%s", manifest.getSeedPack(), manifest.getVersion(), dataset.getCollection()));
            entry.setDisplayName(String.format("Seed: %s@%s/%s", manifest.getSeedPack(), manifest.getVersion(), dataset.getCollection()));
        }

        entry.setSeedPack(manifest.getSeedPack());
        entry.setVersion(manifest.getVersion());
        entry.setDataset(dataset.getCollection());
        entry.setChecksum(checksum);
        entry.setRecords(recordsApplied);
        entry.setAppliedAt(Instant.now());
        entry.setAppliedToRealm(context.getRealm());

        registryRepo.save(context.getRealm(), entry);
    }

    /**
     * Gets all registry entries for a realm, ordered by appliedAt descending.
     *
     * @param realm the realm identifier
     * @return list of registry entries
     */
    public List<SeedRegistryEntry> getHistory(String realm) {
        // Get all entries for the realm
        List<SeedRegistryEntry> allEntries = registryRepo.getAllList(realm);
        // Sort by appliedAt descending, then by seedPack and dataset
        allEntries.sort((a, b) -> {
            if (a.getAppliedAt() == null && b.getAppliedAt() == null) return 0;
            if (a.getAppliedAt() == null) return 1;
            if (b.getAppliedAt() == null) return -1;
            int timeCompare = b.getAppliedAt().compareTo(a.getAppliedAt());
            if (timeCompare != 0) return timeCompare;
            int packCompare = Objects.compare(a.getSeedPack(), b.getSeedPack(), Comparator.naturalOrder());
            if (packCompare != 0) return packCompare;
            return Objects.compare(a.getDataset(), b.getDataset(), Comparator.naturalOrder());
        });
        return allEntries;
    }
}


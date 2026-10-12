package com.e2eq.framework.service.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SeedPlanTest {
    final SeedContext context = SeedContext.builder("pool").tenantId("tenant-a").orgRefName("org").build();
    final Source source = new Source();
    final List<String> writes = new ArrayList<>();
    final Map<String, String> receipts = new HashMap<>();
    final SeedRepository repository = new SeedRepository() {
        public void ensureIndexes(SeedContext c, SeedPackManifest.Dataset d, SeedPackManifest.Index i) { }
        public void upsertRecord(SeedContext c, SeedPackManifest.Dataset d, Map<String, Object> r) {
            writes.add(d.getCollection() + ":" + r.get("refName"));
        }
    };
    final SeedRegistry registry = new SeedRegistry() {
        public boolean shouldApply(SeedContext c, SeedPackManifest m, SeedPackManifest.Dataset d, String hash) { return true; }
        public Optional<String> getLastAppliedChecksum(SeedContext c, SeedPackManifest m, SeedPackManifest.Dataset d) {
            return Optional.ofNullable(receipts.get(m.getSeedPack() + "/" + d.getFile()));
        }
        public void recordApplied(SeedContext c, SeedPackManifest m, SeedPackManifest.Dataset d, String hash, int n) {
            receipts.put(m.getSeedPack() + "/" + d.getFile(), hash);
        }
    };

    SeedLoader loader() { return SeedLoader.builder().addSeedSource(source).seedRepository(repository).seedRegistry(registry).build(); }

    @Test void pinsDependencyClosureAcrossRestartAndNewVersions() throws Exception {
        source.pack("base", "1.0.0", List.of());
        source.pack("app", "1.0.0", List.of("base"));
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        assertEquals(List.of("base", "app"), plan.packs().stream().map(SeedPlan.Pack::name).toList());
        assertTrue(writes.isEmpty());
        // Simulate durable JSON storage and a different loader after a deployment.
        ObjectMapper mapper = new ObjectMapper();
        SeedPlan restored = mapper.readValue(mapper.writeValueAsBytes(plan), SeedPlan.class);
        source.pack("base", "2.0.0", List.of());
        loader().applyPlan(restored, context);
        assertEquals(List.of("base:1.0.0", "app:1.0.0"), writes);
        loader().applyPlan(restored, context);
        assertEquals(2, writes.size(), "matching existing registry receipts suppress repeated writes");
    }

    @Test void changedLaterDatasetRejectsBeforeAnyWrite() {
        source.pack("base", "1.0.0", List.of());
        source.pack("app", "1.0.0", List.of("base"));
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        source.data.put("app@1.0.0", "[{\"refName\":\"changed\"}]");
        assertFailure("SEED_PLAN_DATASET_DRIFT", () -> loader().applyPlan(plan, context));
        assertTrue(writes.isEmpty());
        assertTrue(receipts.isEmpty());
    }

    @Test void changedManifestRejectsEvenWhenDataMatches() {
        var pack = source.pack("app", "1.0.0", List.of());
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        pack.getManifest().getDatasets().get(0).setUpsert(true);
        assertFailure("SEED_PLAN_MANIFEST_DRIFT", () -> loader().applyPlan(plan, context));
        assertTrue(writes.isEmpty());
    }

    @Test void wrongTenantInSamePoolRejects() {
        source.pack("app", "1.0.0", List.of());
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        assertFailure("SEED_PLAN_CONTEXT_MISMATCH", () -> loader().applyPlan(plan,
                SeedContext.builder("pool").tenantId("tenant-b").orgRefName("org").build()));
        assertTrue(writes.isEmpty());
    }

    @Test void missingDependencyAndCyclesFailDuringPlanning() {
        source.pack("app", "1.0.0", List.of("missing"));
        assertThrows(SeedLoadingException.class, () -> loader().plan(List.of(SeedPackRef.parse("app")), context));
        source.pack("missing", "1.0.0", List.of("app"));
        assertFailure("SEED_PLAN_CYCLE", () -> loader().plan(List.of(SeedPackRef.parse("app")), context));
        assertTrue(writes.isEmpty());
    }

    @Test void duplicateIdentityIsNotResolvedBySourceOrder() {
        source.pack("app", "1.0.0", List.of());
        source.pack("app", "1.0.0", List.of());
        assertFailure("SEED_PACK_AMBIGUOUS", () -> loader().plan(List.of(SeedPackRef.parse("app")), context));
    }

    @Test void missingBytesAndUnavailableSourceFailBeforeWriting() {
        source.pack("app", "1.0.0", List.of());
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        source.data.clear();
        assertThrows(SeedLoadingException.class, () -> loader().applyPlan(plan, context));
        source.unavailable = true;
        assertThrows(SeedLoadingException.class, () -> loader().applyPlan(plan, context));
        assertTrue(writes.isEmpty());
    }

    @Test void executionUsesValidatedBytesWithoutReopening() {
        source.pack("app", "1.0.0", List.of());
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        source.changeAfterRead = true;
        loader().applyPlan(plan, context);
        assertEquals(List.of("app:1.0.0"), writes);
        assertEquals(2, source.opens, "once for planning, once for execution preflight");
    }

    @Test void removedDependencyFromStoredPlanFailsClosed() {
        source.pack("base", "1.0.0", List.of());
        source.pack("app", "1.0.0", List.of("base"));
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        SeedPlan broken = new SeedPlan(1, plan.contextSha256(), List.of(plan.packs().get(1)));
        assertFailure("SEED_PLAN_DEPENDENCY_MISMATCH", () -> loader().applyPlan(broken, context));
        assertTrue(writes.isEmpty());
    }

    @Test void incompatibleVersionsDoNotSilentlyApplyBoth() {
        source.pack("base", "1.0.0", List.of());
        source.pack("base", "2.0.0", List.of());
        source.pack("app", "1.0.0", List.of("base@1.0.0"));
        assertFailure("SEED_PLAN_VERSION_CONFLICT", () -> loader().plan(
                List.of(SeedPackRef.parse("app"), SeedPackRef.parse("base@2.0.0")), context));
    }

    @Test void resumesAfterEarlierDatasetReceiptWithoutRepeatingItsWrites() {
        source.pack("base", "1.0.0", List.of());
        source.pack("app", "1.0.0", List.of("base"));
        SeedPlan plan = loader().plan(List.of(SeedPackRef.parse("app")), context);
        SeedRepository interrupted = new SeedRepository() {
            public void ensureIndexes(SeedContext c, SeedPackManifest.Dataset d, SeedPackManifest.Index i) { }
            public void upsertRecord(SeedContext c, SeedPackManifest.Dataset d, Map<String, Object> r) {
                if (d.getCollection().equals("app")) throw new IllegalStateException("process interrupted");
                repository.upsertRecord(c, d, r);
            }
        };
        var first = SeedLoader.builder().addSeedSource(source).seedRepository(interrupted).seedRegistry(registry).build();
        assertThrows(IllegalStateException.class, () -> first.applyPlan(plan, context));
        assertEquals(List.of("base:1.0.0"), writes);
        loader().applyPlan(plan, context);
        assertEquals(List.of("base:1.0.0", "app:1.0.0"), writes);
    }

    @Test void unknownTransformFailsDuringPlanning() {
        var pack = source.pack("app", "1.0.0", List.of());
        var transform = new SeedPackManifest.Transform();
        transform.setType("missing-handler");
        pack.getManifest().getDatasets().get(0).setTransforms(List.of(transform));
        assertThrows(SeedLoadingException.class, () -> loader().plan(List.of(SeedPackRef.parse("app")), context));
        assertTrue(writes.isEmpty());
    }

    @Test void planArchetypePinsItsIncludedPacks() {
        source.pack("base", "1.0.0", List.of());
        var pack = source.pack("app", "1.0.0", List.of());
        var archetype = new SeedPackManifest.Archetype();
        archetype.setName("standard"); archetype.setIncludes(List.of("base"));
        pack.getManifest().setArchetypes(List.of(archetype));
        SeedPlan plan = loader().planArchetype("standard", context);
        loader().applyPlan(plan, context);
        assertEquals(List.of("base:1.0.0", "app:1.0.0"), writes);
    }

    @Test void ambiguousArchetypeOwnersFailInsteadOfChoosingOne() {
        for (String name : List.of("one", "two")) {
            var pack = source.pack(name, "1.0.0", List.of());
            var archetype = new SeedPackManifest.Archetype();
            archetype.setName("standard");
            pack.getManifest().setArchetypes(List.of(archetype));
        }
        assertFailure("SEED_ARCHETYPE_AMBIGUOUS", () -> loader().planArchetype("standard", context));
        assertTrue(writes.isEmpty());
    }

    @Test void applicablePlanningAndPreflightAreReadOnlyAndFailOnUnavailableSource() {
        source.pack("b", "1.0.0", List.of()); source.pack("a", "1.0.0", List.of());
        SeedPlan plan = loader().planApplicable(context);
        assertEquals(List.of("a", "b"), plan.packs().stream().map(SeedPlan.Pack::name).toList());
        loader().validatePlan(plan, context);
        assertTrue(writes.isEmpty()); assertTrue(receipts.isEmpty());
        source.unavailable = true;
        assertThrows(SeedLoadingException.class, () -> loader().planApplicable(context));
    }

    static void assertFailure(String code, Runnable action) {
        assertTrue(assertThrows(SeedLoadingException.class, action::run).getMessage().startsWith(code));
    }

    static class Source implements SeedSource {
        final List<SeedPackDescriptor> packs = new ArrayList<>();
        final Map<String, String> data = new HashMap<>();
        boolean unavailable, changeAfterRead;
        int opens;
        public String getId() { return "test"; }
        public List<SeedPackDescriptor> loadSeedPacks(SeedContext c) throws IOException {
            if (unavailable) throw new IOException("unavailable");
            return packs;
        }
        public InputStream openDataset(SeedPackDescriptor d, String path) throws IOException {
            opens++;
            String content = data.get(d.identity());
            if (content == null) throw new IOException("missing");
            if (changeAfterRead) data.put(d.identity(), "[{\"refName\":\"changed\"}]");
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }
        SeedPackDescriptor pack(String name, String version, List<String> includes) {
            SeedPackManifest manifest = new SeedPackManifest();
            manifest.setSeedPack(name); manifest.setVersion(version); manifest.setIncludes(includes);
            SeedPackManifest.Dataset dataset = new SeedPackManifest.Dataset();
            dataset.setCollection(name); dataset.setFile("rows.json"); dataset.setNaturalKey(List.of("refName"));
            manifest.setDatasets(List.of(dataset));
            var descriptor = new SeedPackDescriptor(this, manifest, Path.of(name, version, "manifest.yaml"));
            packs.add(descriptor); data.put(descriptor.identity(), "[{\"refName\":\"" + version + "\"}]");
            return descriptor;
        }
    }
}

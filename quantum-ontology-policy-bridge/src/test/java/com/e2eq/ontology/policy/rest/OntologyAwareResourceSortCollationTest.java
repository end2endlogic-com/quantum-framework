package com.e2eq.ontology.policy.rest;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that {@link OntologyAwareResource#resolveAggregationSortCollation}
 * — the collation source used by {@code tryAggregationList} — delegates to
 * {@link BaseMorphiaRepo#getSortCollation()} rather than reading collation
 * config independently.
 *
 * <p>Fixes Greptile P1 on PR #21: previously this class carried its own copy
 * of {@code quantum.sort.collation.*} @ConfigProperty fields, so an
 * override of {@link com.e2eq.framework.model.persistent.morphia.MorphiaRepo#getSortCollation()}
 * on a downstream repo would apply to {@code find().sort(...)} but not to
 * aggregation/expand queries. These tests lock in the delegation contract.
 *
 * <p>Uses a JDK dynamic proxy for {@link BaseMorphiaRepo} so no Mockito
 * dependency is required.
 */
class OntologyAwareResourceSortCollationTest {

    public static class SortCollationTestModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }

        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    static class TestOntologyResource
            extends OntologyAwareResource<SortCollationTestModel, BaseMorphiaRepo<SortCollationTestModel>> {
        TestOntologyResource(BaseMorphiaRepo<SortCollationTestModel> repo) {
            super(repo);
        }
    }

    @SuppressWarnings("unchecked")
    private static BaseMorphiaRepo<SortCollationTestModel> stubRepoReturning(
            Collation collation, AtomicInteger callCount) {
        return (BaseMorphiaRepo<SortCollationTestModel>) Proxy.newProxyInstance(
                BaseMorphiaRepo.class.getClassLoader(),
                new Class<?>[]{BaseMorphiaRepo.class},
                (proxy, method, args) -> {
                    if ("getSortCollation".equals(method.getName())) {
                        callCount.incrementAndGet();
                        return collation;
                    }
                    throw new UnsupportedOperationException("Not stubbed: " + method);
                });
    }

    @Test
    void delegatesToRepoGetSortCollation_whenSortPresent() {
        Collation configured = Collation.builder()
                .locale("en")
                .collationStrength(CollationStrength.SECONDARY)
                .build();
        AtomicInteger repoCalls = new AtomicInteger();
        TestOntologyResource resource = new TestOntologyResource(stubRepoReturning(configured, repoCalls));

        Collation result = resource.resolveAggregationSortCollation(List.of("displayName"));

        assertSame(configured, result, "Aggregation path must return the repo's collation, not build its own");
        assertEquals(1, repoCalls.get(), "Repo hook should be consulted exactly once per resolve call");
    }

    @Test
    void returnsNull_andSkipsRepoLookup_whenSortEmpty() {
        AtomicInteger repoCalls = new AtomicInteger();
        TestOntologyResource resource = new TestOntologyResource(
                stubRepoReturning(Collation.builder().locale("en").build(), repoCalls));

        Collation result = resource.resolveAggregationSortCollation(List.of());

        assertNull(result, "Empty sort must skip collation to avoid unnecessary in-memory sort");
        assertEquals(0, repoCalls.get(),
                "Repo hook should not be invoked when there is nothing to sort");
    }

    @Test
    void returnsNull_whenSortNull() {
        AtomicInteger repoCalls = new AtomicInteger();
        TestOntologyResource resource = new TestOntologyResource(
                stubRepoReturning(Collation.builder().locale("en").build(), repoCalls));

        Collation result = resource.resolveAggregationSortCollation(null);

        assertNull(result);
        assertEquals(0, repoCalls.get());
    }

    @Test
    void returnsNull_whenRepoHookReturnsNull() {
        AtomicInteger repoCalls = new AtomicInteger();
        TestOntologyResource resource = new TestOntologyResource(stubRepoReturning(null, repoCalls));

        Collation result = resource.resolveAggregationSortCollation(List.of("displayName"));

        assertNull(result, "Null from repo hook must preserve upstream binary-order aggregation sort");
        assertEquals(1, repoCalls.get());
    }
}

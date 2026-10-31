package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import dev.morphia.query.FindOptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link MorphiaRepo#getSortCollation()} and the collation
 * portion of {@link MorphiaRepo#buildFindOptions(int, int, List, List)}.
 *
 * <p>These are plain JUnit tests (no {@code @QuarkusTest}) that exercise the
 * config-driven collation hook directly, so no Mongo or CDI container is
 * required. See {@link RepoSecurityContextResolverTest} for the same pattern.
 */
class MorphiaRepoSortCollationTest {

    /** Minimal concrete entity so {@link MorphiaRepo} can be parameterized. */
    public static class SortCollationTestModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() { return "test-area"; }

        @Override
        public String bmFunctionalDomain() { return "test-domain"; }
    }

    /**
     * Test subclass that exposes the protected {@code buildFindOptions} and
     * lets tests set the two collation config fields directly, bypassing
     * Quarkus @ConfigProperty injection.
     */
    static class TestRepo extends MorphiaRepo<SortCollationTestModel> {
        TestRepo(String locale, int strength) {
            this.sortCollationLocale = Optional.ofNullable(locale);
            this.sortCollationStrength = strength;
        }

        FindOptions callBuildFindOptions(List<SortField> sortFields) {
            try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
                return buildFindOptions(0, 0, sortFields, null);
            }
        }
    }

    /** Test subclass that overrides the hook to return a fixed value. */
    static class HookOverrideRepo extends MorphiaRepo<SortCollationTestModel> {
        private final Collation override;

        HookOverrideRepo(Collation override) {
            this.override = override;
        }

        @Override
        public Collation getSortCollation() {
            return override;
        }

        FindOptions callBuildFindOptions(List<SortField> sortFields) {
            try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
                return buildFindOptions(0, 0, sortFields, null);
            }
        }
    }

    private static Collation extractCollation(FindOptions options) throws ReflectiveOperationException {
        Field field = FindOptions.class.getDeclaredField("collation");
        field.setAccessible(true);
        return (Collation) field.get(options);
    }

    @Test
    void getSortCollation_returnsNull_whenLocaleBlank() {
        TestRepo repo = new TestRepo("", 2);

        assertNull(repo.getSortCollation(), "Blank locale should disable sort collation (upstream default)");
    }

    @Test
    void getSortCollation_returnsConfiguredCollation() {
        TestRepo repo = new TestRepo("en", 2);

        Collation collation = repo.getSortCollation();

        assertNotNull(collation, "Configured locale should produce a Collation");
        assertEquals("en", collation.getLocale());
        assertEquals(CollationStrength.SECONDARY, collation.getStrength());
    }

    @Test
    void getSortCollation_returnsNull_whenStrengthOutOfRange() {
        TestRepo repo = new TestRepo("en", 99);

        assertNull(
                repo.getSortCollation(),
                "Invalid strength should be swallowed by the try/catch and disable collation (not throw)");
    }

    @Test
    void buildFindOptions_appliesCollation_whenHookNonNullAndSortPresent() throws Exception {
        Collation fixed = Collation.builder()
                .locale("en")
                .collationStrength(CollationStrength.SECONDARY)
                .build();
        HookOverrideRepo repo = new HookOverrideRepo(fixed);

        FindOptions options = repo.callBuildFindOptions(
                List.of(new SortField("displayName", SortField.SortDirection.ASC)));

        assertSame(fixed, extractCollation(options), "Collation from hook should be forwarded to FindOptions");
    }

    @Test
    void buildFindOptions_noCollation_whenSortEmpty() throws Exception {
        Collation fixed = Collation.builder()
                .locale("en")
                .collationStrength(CollationStrength.SECONDARY)
                .build();
        HookOverrideRepo repo = new HookOverrideRepo(fixed);

        FindOptions options = repo.callBuildFindOptions(List.of());

        assertNull(
                extractCollation(options),
                "Collation must not be applied when no sort is requested (avoids unnecessary in-memory sort)");
    }

    @Test
    void buildFindOptions_noCollation_whenHookReturnsNull() throws Exception {
        HookOverrideRepo repo = new HookOverrideRepo(null);

        FindOptions options = repo.callBuildFindOptions(
                List.of(new SortField("displayName", SortField.SortDirection.ASC)));

        assertNull(
                extractCollation(options),
                "Null from hook must preserve upstream binary-order sort");
    }
}

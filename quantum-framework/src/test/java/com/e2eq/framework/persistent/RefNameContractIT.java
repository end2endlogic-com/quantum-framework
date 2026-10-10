package com.e2eq.framework.persistent;

import com.e2eq.framework.exceptions.RefNameViolationException;
import com.e2eq.framework.exceptions.RefNameViolationException.Code;
import com.e2eq.framework.model.persistent.morphia.CounterRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.persistent.base.Counter;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.SecurityUtils;
import dev.morphia.query.filters.Filters;
import com.e2eq.framework.test.MongoDbInitResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonical refName contract for {@code @CanonicalRefName} types (UserGroup, Policy), against the
 * replica-set Mongo the framework tests use: create-time rule, immutability, legacy admission, and the
 * typed REST error.
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
@TestSecurity(user = "system@system.com", roles = {"admin"})
public class RefNameContractIT extends BaseRepoTest {

    private static final String REALM = "test-quantum-com";

    @Inject
    UserGroupRepo userGroupRepo;

    @Inject
    PolicyRepo policyRepo;

    @Inject
    CounterRepo counterRepo;

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Inject
    SecurityUtils securityUtils;

    private final List<ObjectId> createdGroups = new ArrayList<>();
    private final List<ObjectId> createdPolicies = new ArrayList<>();
    private final List<ObjectId> createdCounters = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        var ds = morphiaDataStoreWrapper.getDataStore(REALM);
        if (!createdGroups.isEmpty()) {
            ds.find(UserGroup.class).filter(Filters.in("_id", createdGroups)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        if (!createdPolicies.isEmpty()) {
            ds.find(Policy.class).filter(Filters.in("_id", createdPolicies)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        if (!createdCounters.isEmpty()) {
            ds.find(Counter.class).filter(Filters.in("_id", createdCounters)).delete(new dev.morphia.DeleteOptions().multi(true));
        }
        createdGroups.clear();
        createdPolicies.clear();
        createdCounters.clear();
    }

    private static String unique(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    private static UserGroup group(String refName) {
        UserGroup g = new UserGroup();
        g.setRefName(refName);
        g.setDisplayName("RefName contract IT");
        return g;
    }

    private UserGroup saveGroup(UserGroup g) {
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            UserGroup saved = userGroupRepo.save(REALM, g);
            createdGroups.add(saved.getId());
            return saved;
        }
    }

    private RefNameViolationException expectViolation(Code expected, Supplier<?> write) {
        RefNameViolationException ex;
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            ex = assertThrows(RefNameViolationException.class, write::get);
        }
        assertEquals(expected, ex.getCode(), ex.getMessage());
        return ex;
    }

    @Test
    void createsGroupWithCanonicalRefName() {
        String refName = unique("IT_GROUP");
        UserGroup saved = saveGroup(group(refName));
        assertEquals(refName, saved.getRefName());
    }

    @Test
    void rejectsNonCanonicalRefNamesOnCreateWithoutNormalising() {
        expectViolation(Code.REFNAME_INVALID_FORMAT, () -> userGroupRepo.save(REALM, group("ops_team")));
        expectViolation(Code.REFNAME_INVALID_FORMAT, () -> userGroupRepo.save(REALM, group("OPS-TEAM")));
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            // UnversionedBaseModel's @Size(min=3) method validation rejects this before the repository runs.
            assertThrows(jakarta.validation.ConstraintViolationException.class,
                    () -> userGroupRepo.save(REALM, group("AB")));
        }
        expectViolation(Code.REFNAME_GENERATED_ID,
                () -> userGroupRepo.save(REALM, group(new ObjectId().toHexString())));

        RefNameViolationException ex = expectViolation(Code.REFNAME_INVALID_FORMAT,
                () -> policyRepo.save(REALM, policy("tenant_admin_policy")));
        assertEquals("Policy", ex.getEntityType());
        assertEquals("tenant_admin_policy", ex.getOfferedRefName());
    }

    @Test
    void missingRefNameIsRejectedNotDefaultedToObjectId() {
        RefNameViolationException ex = expectViolation(Code.REFNAME_REQUIRED,
                () -> userGroupRepo.save(REALM, group(null)));
        assertEquals("UserGroup", ex.getEntityType());
    }

    @Test
    void rejectsRefNameChangeOnSaveMergeAndFieldUpdate() {
        String refName = unique("IT_IMMUTABLE");
        UserGroup saved = saveGroup(group(refName));

        UserGroup renamed = group(unique("IT_RENAMED"));
        renamed.setId(saved.getId());
        RefNameViolationException ex = expectViolation(Code.REFNAME_IMMUTABLE,
                () -> userGroupRepo.save(REALM, renamed));
        assertEquals(refName, ex.getStoredRefName());
        assertEquals(saved.getId().toHexString(), ex.getEntityId());

        expectViolation(Code.REFNAME_IMMUTABLE,
                () -> userGroupRepo.merge(morphiaDataStoreWrapper.getDataStore(REALM), renamed));

        UserGroup cleared = group(null);
        cleared.setId(saved.getId());
        expectViolation(Code.REFNAME_IMMUTABLE, () -> userGroupRepo.save(REALM, cleared));

        expectViolation(Code.REFNAME_IMMUTABLE, () -> {
            try {
                return userGroupRepo.update(REALM, saved.getId().toHexString(),
                        Pair.of("refName", (Object) unique("IT_RENAMED")));
            } catch (com.e2eq.framework.model.persistent.InvalidStateTransitionException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void skipValidationDoesNotBypassImmutability() {
        UserGroup saved = saveGroup(group(unique("IT_SKIP")));
        UserGroup renamed = group(unique("IT_SKIP_RENAMED"));
        renamed.setId(saved.getId());
        renamed.setSkipValidation(true);
        expectViolation(Code.REFNAME_IMMUTABLE, () -> userGroupRepo.save(REALM, renamed));
    }

    @Test
    void existingNonCanonicalRecordsStillSaveUnchanged() {
        // Records that predate the rule: rename a stored group behind the repository's back.
        UserGroup stored = saveGroup(group(unique("IT_PRE_RULE")));
        String legacyRefName = "pre-rule-group-" + System.nanoTime();
        var ds = morphiaDataStoreWrapper.getDataStore(REALM);
        ds.getDatabase()
                .getCollection(ds.getMapper().getEntityModel(UserGroup.class).collectionName())
                .updateOne(new org.bson.Document("_id", stored.getId()),
                        new org.bson.Document("$set", new org.bson.Document("refName", legacyRefName)));

        UserGroup legacy;
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            legacy = userGroupRepo.findById(stored.getId().toHexString(), REALM).orElseThrow();
            legacy.setDisplayName("edited after the rule shipped");
            UserGroup saved = userGroupRepo.save(REALM, legacy);
            assertEquals(legacyRefName, saved.getRefName());
        }
    }

    @Test
    void migratedRealmHoldsBuiltInLegacyNames() {
        // BaseRepoTest migrated the realm through MorphiaRepo; the changeset-created policy got in
        // because it is declared on @CanonicalRefName(legacy = ...).
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            assertTrue(policyRepo.findByRefName("defaultAnonymousPolicy", REALM).isPresent());
        }
    }

    @Test
    void declaredLegacyNamesAreAdmitted() {
        // Declared in test application.properties: quantum.refname.legacy-allowed.UserGroup
        UserGroup saved = saveGroup(group("refname-it-legacy-group"));
        assertNotNull(saved.getId());
    }

    @Test
    void typesWithoutTheAnnotationAreUnaffected() {
        Counter counter = new Counter();
        counter.setRefName("refname-it-counter-" + System.nanoTime());
        Counter saved;
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            saved = counterRepo.save(REALM, counter);
        }
        createdCounters.add(saved.getId());
        assertEquals(counter.getRefName(), saved.getRefName());
    }

    @Test
    void restCreateReturnsTypedBadRequest() {
        Map<String, Object> entity = new HashMap<>();
        entity.put("refName", "ops_team");
        entity.put("displayName", "Ops team");
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", UserGroup.class.getName());
        body.put("entity", entity);

        given().header("X-Realm", REALM)
                .contentType(ContentType.JSON)
                .body(body)
        .when()
                .post("/api/query/save")
        .then()
                .statusCode(400)
                .body("errorCode", is("REFNAME_INVALID_FORMAT"))
                .body("diagnostics.entityType", is("UserGroup"))
                .body("diagnostics.offeredRefName", is("ops_team"));
    }

    @Test
    void restRenameReturnsTypedConflict() {
        // Create through the gateway so the record sits in the caller's governed data scope.
        String refName = unique("IT_REST");
        Map<String, Object> created = new HashMap<>();
        created.put("refName", refName);
        created.put("displayName", "created");
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("rootType", UserGroup.class.getName());
        createBody.put("entity", created);
        String id = given().header("X-Realm", REALM)
                .contentType(ContentType.JSON)
                .body(createBody)
        .when()
                .post("/api/query/save")
        .then()
                .statusCode(200)
                .extract().path("id");
        createdGroups.add(new ObjectId(id));

        Map<String, Object> entity = new HashMap<>();
        entity.put("id", id);
        entity.put("refName", unique("IT_REST_RENAMED"));
        entity.put("displayName", "renamed");
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", UserGroup.class.getName());
        body.put("entity", entity);

        given().header("X-Realm", REALM)
                .contentType(ContentType.JSON)
                .body(body)
        .when()
                .post("/api/query/save")
        .then()
                .statusCode(409)
                .body("errorCode", is("REFNAME_IMMUTABLE"))
                .body("diagnostics.storedRefName", is(refName));
    }

    @Test
    void mergeWithoutRefNameLeavesItUntouched() {
        String refName = unique("IT_MERGE");
        UserGroup saved = saveGroup(group(refName));

        UserGroup partial = new UserGroup();
        partial.setId(saved.getId());
        partial.setVersion(saved.getVersion());
        partial.setDisplayName("merged display name");
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            userGroupRepo.merge(morphiaDataStoreWrapper.getDataStore(REALM), partial);
            UserGroup reloaded = userGroupRepo.findById(saved.getId().toHexString(), REALM).orElseThrow();
            assertEquals(refName, reloaded.getRefName());
            assertEquals("merged display name", reloaded.getDisplayName());
        }

        // A full replace without refName would persist a different value, so it is still a rename.
        UserGroup cleared = group(null);
        cleared.setId(saved.getId());
        expectViolation(Code.REFNAME_IMMUTABLE, () -> userGroupRepo.save(REALM, cleared));
    }

    @Test
    void recordStoredWithoutRefNameCanBeEditedOrNamedOnce() {
        UserGroup stored = saveGroup(group(unique("IT_NULL_REF")));
        var ds = morphiaDataStoreWrapper.getDataStore(REALM);
        ds.getDatabase()
                .getCollection(ds.getMapper().getEntityModel(UserGroup.class).collectionName())
                .updateOne(new org.bson.Document("_id", stored.getId()),
                        new org.bson.Document("$unset", new org.bson.Document("refName", "")));

        try (var scope = SecurityCallScope.openIgnoringRules()) {
            UserGroup unnamed = userGroupRepo.findById(stored.getId().toHexString(), REALM).orElseThrow();
            unnamed.setDisplayName("still unnamed");
            UserGroup kept = userGroupRepo.save(REALM, unnamed);
            assertEquals(null, kept.getRefName(), "a canonical type is never given a generated refName");
        }

        UserGroup badName = group("not_canonical");
        badName.setId(stored.getId());
        expectViolation(Code.REFNAME_INVALID_FORMAT, () -> userGroupRepo.save(REALM, badName));

        String refName = unique("IT_NAMED_LATER");
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            UserGroup named = userGroupRepo.findById(stored.getId().toHexString(), REALM).orElseThrow();
            named.setRefName(refName);
            assertEquals(refName, userGroupRepo.save(REALM, named).getRefName());
        }
    }

    @Test
    void batchSaveChecksEveryEntity() {
        UserGroup existing = saveGroup(group(unique("IT_BATCH_EXISTING")));
        UserGroup renamed = group(unique("IT_BATCH_RENAMED"));
        renamed.setId(existing.getId());
        UserGroup fresh = group(unique("IT_BATCH_NEW"));

        RefNameViolationException ex = expectViolation(Code.REFNAME_IMMUTABLE,
                () -> userGroupRepo.save(morphiaDataStoreWrapper.getDataStore(REALM), List.of(fresh, renamed)));
        assertEquals(existing.getId().toHexString(), ex.getEntityId());
    }

    @Test
    void restCreateCannotClaimReservedLegacyName() {
        Map<String, Object> entity = new HashMap<>();
        entity.put("refName", "tenant-admin-users");
        entity.put("displayName", "Tenant admins");
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", UserGroup.class.getName());
        body.put("entity", entity);

        given().header("X-Realm", REALM)
                .contentType(ContentType.JSON)
                .body(body)
        .when()
                .post("/api/query/save")
        .then()
                .statusCode(400)
                .body("errorCode", is("REFNAME_RESERVED"));
    }

    @Test
    void restShortRefNameCarriesTypedErrorCode() {
        Map<String, Object> entity = new HashMap<>();
        entity.put("refName", "AB");
        entity.put("displayName", "Too short");
        Map<String, Object> body = new HashMap<>();
        body.put("rootType", UserGroup.class.getName());
        body.put("entity", entity);

        given().header("X-Realm", REALM)
                .contentType(ContentType.JSON)
                .body(body)
        .when()
                .post("/api/query/save")
        .then()
                .statusCode(400)
                .body("errorCode", is("REFNAME_INVALID_FORMAT"))
                .body("diagnostics.offeredRefName", is("AB"));
    }

    @Test
    void policyImportUpdatesExistingAndReportsTypedRowErrors() {
        String refName = unique("IT_IMPORTED_POLICY");
        String yaml = "- refName: " + refName + "\n"
                + "  principalId: admin\n"
                + "  principalType: ROLE\n"
                + "- refName: not_canonical\n"
                + "  principalId: admin\n"
                + "  principalType: ROLE\n";

        given().header("X-Realm", REALM)
                .contentType("text/plain")
                .body(yaml)
        .when()
                .post("/security/permission/policies/import")
        .then()
                .statusCode(200)
                .body("createdCount", is(1))
                .body("errors[0].errorCode", is("REFNAME_INVALID_FORMAT"));
        try (var scope = SecurityCallScope.openIgnoringRules()) {
            createdPolicies.add(policyRepo.findByRefName(refName, REALM).orElseThrow().getId());
        }

        // Re-importing the same refName must update the stored policy, not try to create it again.
        given().header("X-Realm", REALM)
                .contentType("text/plain")
                .body(yaml)
        .when()
                .post("/security/permission/policies/import")
        .then()
                .statusCode(200)
                .body("createdCount", is(0))
                .body("updatedCount", is(1));

        var ds = morphiaDataStoreWrapper.getDataStore(REALM);
        long copies = ds.getDatabase()
                .getCollection(ds.getMapper().getEntityModel(Policy.class).collectionName())
                .countDocuments(new org.bson.Document("refName", refName));
        assertEquals(1, copies, "re-import must not duplicate the policy");
    }

    private static Policy policy(String refName) {
        Policy p = new Policy();
        p.setRefName(refName);
        p.setPrincipalType(Policy.PrincipalType.ROLE);
        p.setPrincipalId("admin");
        return p;
    }
}

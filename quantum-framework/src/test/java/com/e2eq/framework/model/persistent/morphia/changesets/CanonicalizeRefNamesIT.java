package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.migration.refname.CanonicalRefNameMigrator;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Action;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Kind;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.test.MongoDbInitResource;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical-refName migration against replica-set Mongo. Pre-rule records are planted the way they exist in the
 * field: saved under a canonical name, then renamed in the collection to the old spelling (the repositories refuse to
 * create or rename to one).
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
@TestSecurity(user = "system@system.com", roles = {"admin", "system"})
public class CanonicalizeRefNamesIT extends BaseRepoTest {

   private static final String REALM = "test-quantum-com";

   @Inject
   CanonicalizeRefNames changeSet;

   @Inject
   CanonicalRefNameMigrator migrator;

   @Inject
   UserGroupRepo userGroupRepo;

   @Inject
   PolicyRepo policyRepo;

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   @Inject
   MongoClient mongoClient;

   private final String run = String.valueOf(System.nanoTime());
   private final List<ObjectId> groups = new ArrayList<>();
   private final List<ObjectId> policies = new ArrayList<>();
   private final List<String> scratchRealms = new ArrayList<>();

   @AfterEach
   void cleanUp() {
      collection(REALM, "userGroup").deleteMany(Filters.in("_id", groups));
      collection(REALM, "policy").deleteMany(Filters.in("_id", policies));
      scratchRealms.forEach(realm -> mongoClient.getDatabase(realm).drop());
   }

   @Test
   void reportsThenAppliesRenamesAndMergesWithoutChangingGrants() {
      // RENAME: no canonical record yet; members stay where they are.
      ObjectId renamed = legacyGroup("it-ops-" + run, List.of("ops"), "ann@it.com");
      ObjectId renamedPolicy = legacyPolicy("it-ops-policy-" + run, "ops", RuleEffect.ALLOW);
      // MERGE: an equivalent canonical group and policy exist in the same data domain.
      ObjectId canonicalGroup = group("IT_DUP_" + run, List.of("dup"), "bob@it.com");
      ObjectId duplicateGroup = legacyGroup("it-dup-" + run, List.of("dup"), "cid@it.com");
      ObjectId canonicalPolicy = policy("IT_DUP_POLICY_" + run, "dup", RuleEffect.ALLOW);
      ObjectId duplicatePolicy = legacyPolicy("it-dup-policy-" + run, "dup", RuleEffect.ALLOW);
      // CONFLICT: the canonical records differ (roles, rule effect).
      group("IT_DIFF_" + run, List.of("diff"), "dan@it.com");
      ObjectId differingGroup = legacyGroup("it-diff-" + run, List.of("diff", "extra"), "eve@it.com");
      policy("IT_DIFF_POLICY_" + run, "diff", RuleEffect.ALLOW);
      ObjectId differingPolicy = legacyPolicy("it-diff-policy-" + run, "diff", RuleEffect.DENY);
      // CONFLICT: two old spellings of one name.
      ObjectId ambiguousA = legacyGroup("it-amb-" + run, List.of("amb"), null);
      ObjectId ambiguousB = legacyGroup("it.amb." + run, List.of("amb"), null);
      // UNRESOLVABLE: no canonical spelling exists.
      ObjectId leadingDigit = legacyGroup("9-it-" + run, List.of("x"), null);
      // A same-named group in another data domain is not this record's twin: RENAME, never MERGE across tenants.
      ObjectId otherTenantGroup = group("IT_XD_" + run, List.of("xd"), "fay@it.com");
      collection(REALM, "userGroup").updateOne(Filters.eq("_id", otherTenantGroup),
            Updates.set("dataDomain.tenantId", "other-tenant-" + run));
      ObjectId crossDomain = legacyGroup("it-xd-" + run, List.of("xd"), "gus@it.com");

      Map<ObjectId, Action> plan = plan(REALM);
      assertAction(plan, renamed, Kind.RENAME, "IT_OPS_" + run);
      assertAction(plan, renamedPolicy, Kind.RENAME, "IT_OPS_POLICY_" + run);
      assertAction(plan, duplicateGroup, Kind.MERGE, "IT_DUP_" + run);
      assertEquals(canonicalGroup.toHexString(), plan.get(duplicateGroup).targetId());
      assertAction(plan, duplicatePolicy, Kind.MERGE, "IT_DUP_POLICY_" + run);
      assertAction(plan, differingGroup, Kind.CONFLICT, "IT_DIFF_" + run);
      assertTrue(plan.get(differingGroup).reason().contains("roles"), plan.get(differingGroup).reason());
      assertAction(plan, differingPolicy, Kind.CONFLICT, "IT_DIFF_POLICY_" + run);
      assertTrue(plan.get(differingPolicy).reason().contains("rules"), plan.get(differingPolicy).reason());
      assertAction(plan, ambiguousA, Kind.CONFLICT, "IT_AMB_" + run);
      assertAction(plan, ambiguousB, Kind.CONFLICT, "IT_AMB_" + run);
      assertAction(plan, leadingDigit, Kind.UNRESOLVABLE, null);
      assertAction(plan, crossDomain, Kind.RENAME, "IT_XD_" + run);

      // The change set only reports.
      try (MorphiaSession session = morphiaDataStoreWrapper.getDataStore(REALM).startSession()) {
         changeSet.execute(session, mongoClient, emitter());
      }
      assertEquals("it-ops-" + run, refName(REALM, "userGroup", renamed));
      assertEquals(plan.keySet(), plan(REALM).keySet());

      // GET reports the same plan without changing anything.
      given().when().get("/system/migration/refnames/" + REALM)
            .then().statusCode(200)
            .body("realm", is(REALM))
            .body("actions.find { it.id == '" + renamed.toHexString() + "' }.kind", is("RENAME"))
            .body("actions.find { it.id == '" + duplicatePolicy.toHexString() + "' }.kind", is("MERGE"));
      assertEquals("it-ops-" + run, refName(REALM, "userGroup", renamed));

      // POST applies it.
      given().when().post("/system/migration/refnames/" + REALM + "/apply").then().statusCode(200);
      assertEquals("IT_OPS_" + run, refName(REALM, "userGroup", renamed));
      assertEquals("IT_OPS_POLICY_" + run, refName(REALM, "policy", renamedPolicy));
      assertTrue(memberOf("ann@it.com").contains("IT_OPS_" + run), "membership survives the rename");
      assertNull(refName(REALM, "userGroup", duplicateGroup));
      assertNull(refName(REALM, "policy", duplicatePolicy));
      assertEquals("IT_DUP_POLICY_" + run, refName(REALM, "policy", canonicalPolicy));
      assertTrue(memberOf("cid@it.com").contains("IT_DUP_" + run), "merged members join the canonical group");
      assertTrue(memberOf("bob@it.com").contains("IT_DUP_" + run));
      assertEquals("it-diff-" + run, refName(REALM, "userGroup", differingGroup));
      assertEquals("it-diff-policy-" + run, refName(REALM, "policy", differingPolicy));
      assertEquals("it-amb-" + run, refName(REALM, "userGroup", ambiguousA));
      assertEquals("9-it-" + run, refName(REALM, "userGroup", leadingDigit));
      assertEquals("IT_XD_" + run, refName(REALM, "userGroup", crossDomain));
      assertEquals(List.of("IT_XD_" + run), memberOf("gus@it.com"));
      assertEquals(List.of("IT_XD_" + run), memberOf("fay@it.com"), "the other tenant's group keeps its members");
      assertTrue(memberOf("gus@it.com").size() == 1 && refName(REALM, "userGroup", otherTenantGroup) != null);

      // Applying again changes nothing and leaves only what an operator must decide.
      given().when().post("/system/migration/refnames/" + REALM + "/apply").then().statusCode(200);
      Map<ObjectId, Action> remaining = plan(REALM);
      assertEquals(5, remaining.size(), remaining.toString());
      assertTrue(remaining.values().stream().allMatch(action ->
            action.kind() == Kind.CONFLICT || action.kind() == Kind.UNRESOLVABLE), remaining.toString());
   }

   @Test
   void aDeclaredLegacyNameIsKeptAndHoldsItsName() {
      // quantum.refname.legacy-allowed.UserGroup includes refname_it_legacy (test configuration).
      ObjectId legacy = legacyGroup("refname_it_legacy", List.of("legacy"), null);
      ObjectId shadow = legacyGroup("Refname-It-Legacy", List.of("legacy"), null);
      // Same data domain, so both names fall in one slot of the refName index.
      Document domain = collection(REALM, "userGroup").find(Filters.eq("_id", shadow)).first().get("dataDomain", Document.class);
      collection(REALM, "userGroup").updateOne(Filters.eq("_id", legacy), Updates.set("dataDomain", domain));
      Map<ObjectId, Action> plan = migrator.plan(REALM).actions().stream()
            .filter(action -> List.of(legacy.toHexString(), shadow.toHexString()).contains(action.id()))
            .collect(Collectors.toMap(action -> new ObjectId(action.id()), Function.identity()));
      assertNull(plan.get(legacy), "a declared legacy name is never renamed");
      assertAction(plan, shadow, Kind.CONFLICT, "REFNAME_IT_LEGACY");
      assertTrue(plan.get(shadow).reason().contains("declared legacy"), plan.get(shadow).reason());
   }

   @Test
   void plansAndAppliesInARealmWhoseDatastoreNeverMappedTheTypes() {
      // A tenant realm leaves system-plane types unmapped; planning reads its collections anyway.
      String realm = "test-refname-tenant-" + run;
      scratchRealms.add(realm);
      ObjectId id = new ObjectId();
      mongoClient.getDatabase(realm).getCollection("policy").insertOne(new Document("_id", id)
            .append("refName", "tenant-only-policy").append("principalId", "user").append("principalType", "ROLE")
            .append("applicationId", "*").append("realmRefName", "*").append("rules", List.of())
            .append("dataDomain", new Document("orgRefName", "t.com").append("tenantId", "t.com")
                  .append("ownerId", "owner").append("accountNum", "0000000001").append("dataSegment", 0)));
      RefNameMigrationPlan plan = migrator.plan(realm);
      assertEquals(1, plan.actions().size(), plan.actions().toString());
      assertEquals(Kind.RENAME, plan.actions().get(0).kind());

      try (MorphiaSession session = morphiaDataStoreWrapper.getDataStore(realm).startSession()) {
         session.startTransaction();
         migrator.apply(session, plan, message -> { });
         session.commitTransaction();
      }
      assertEquals("TENANT_ONLY_POLICY", refName(realm, "policy", id));
      // The REST surface accepts only realms in the catalog, never an arbitrary database.
      given().when().get("/system/migration/refnames/" + realm).then().statusCode(404);
      given().when().get("/system/migration/refnames/admin").then().statusCode(404);
   }

   @Test
   @TestSecurity(user = "tenant-admin@it.com", roles = {"admin"})
   void onlyTheSystemRoleMayReadOrApply() {
      given().when().get("/system/migration/refnames/" + REALM).then().statusCode(403);
      given().when().post("/system/migration/refnames/" + REALM + "/apply").then().statusCode(403);
   }

   /** This run's actions, keyed by record id. */
   private Map<ObjectId, Action> plan(String realm) {
      return migrator.plan(realm).actions().stream()
            .filter(action -> action.from().contains(run))
            .collect(Collectors.toMap(action -> new ObjectId(action.id()), Function.identity()));
   }

   private static void assertAction(Map<ObjectId, Action> plan, ObjectId id, Kind kind, String to) {
      Action action = plan.get(id);
      assertEquals(kind, action == null ? null : action.kind(), String.valueOf(action));
      assertEquals(to, action.to());
   }

   private ObjectId group(String refName, List<String> roles, String member) {
      UserGroup group = new UserGroup();
      group.setRefName(refName);
      group.setDisplayName("refName migration IT");
      group.setApplicationId("refname-it");
      group.setRoles(roles);
      if (member != null) {
         EntityReference profile = new EntityReference();
         profile.setEntityRefName(member);
         profile.setEntityDisplayName(member);
         profile.setEntityType("UserProfile");
         group.setUserProfiles(new ArrayList<>(List.of(profile)));
      }
      try (var ignored = SecurityCallScope.openIgnoringRules()) {
         ObjectId id = userGroupRepo.save(REALM, group).getId();
         groups.add(id);
         return id;
      }
   }

   private ObjectId legacyGroup(String legacyName, List<String> roles, String member) {
      ObjectId id = group("IT_PLANT_" + System.nanoTime(), roles, member);
      collection(REALM, "userGroup").updateOne(Filters.eq("_id", id), Updates.set("refName", legacyName));
      return id;
   }

   private ObjectId policy(String refName, String role, RuleEffect effect) {
      SecurityURIHeader header = new SecurityURIHeader.Builder()
            .withIdentity(role).withArea("*").withFunctionalDomain("*").withAction("VIEW").build();
      SecurityURIBody body = new SecurityURIBody.Builder()
            .withRealm("*").withOrgRefName("*").withAccountNumber("*").withTenantId("*")
            .withOwnerId("*").withDataSegment("*").build();
      Policy policy = new Policy();
      policy.setRefName(refName);
      policy.setDisplayName("refName migration IT");
      policy.setPrincipalId(role);
      policy.setPrincipalType(Policy.PrincipalType.ROLE);
      policy.setApplicationId("*");
      policy.setRealmRefName("*");
      policy.setRules(new ArrayList<>(List.of(new Rule.Builder()
            .withName(role + "-view")
            .withSecurityURI(new SecurityURI(header, body))
            .withEffect(effect)
            .withPriority(50)
            .build())));
      try (var ignored = SecurityCallScope.openIgnoringRules()) {
         ObjectId id = policyRepo.save(REALM, policy).getId();
         policies.add(id);
         return id;
      }
   }

   private ObjectId legacyPolicy(String legacyName, String role, RuleEffect effect) {
      ObjectId id = policy("IT_PLANT_" + System.nanoTime(), role, effect);
      collection(REALM, "policy").updateOne(Filters.eq("_id", id), Updates.set("refName", legacyName));
      return id;
   }

   private String refName(String realm, String collection, ObjectId id) {
      Document row = collection(realm, collection).find(Filters.eq("_id", id)).first();
      return row == null ? null : row.getString("refName");
   }

   private List<String> memberOf(String member) {
      EntityReference profile = new EntityReference();
      profile.setEntityRefName(member);
      profile.setEntityDisplayName(member);
      return userGroupRepo.findByUserProfileRefWithIgnoreRules(REALM, profile).stream()
            .map(UserGroup::getRefName).toList();
   }

   private MongoCollection<Document> collection(String realm, String name) {
      return mongoClient.getDatabase(realm).getCollection(name);
   }

   @SuppressWarnings("unchecked")
   private static MultiEmitter<? super String> emitter() {
      return (MultiEmitter<? super String>) Proxy.newProxyInstance(CanonicalizeRefNamesIT.class.getClassLoader(),
            new Class<?>[]{MultiEmitter.class},
            (proxy, method, args) -> "emit".equals(method.getName()) ? proxy : null);
   }
}

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
import io.quarkus.arc.ClientProxy;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical-refName change set against replica-set Mongo and the real repositories. Pre-rule records are
 * planted the way they exist in the field: saved under a canonical name, then renamed in the collection to the old
 * spelling (the repositories refuse to create or rename to one).
 */
@QuarkusTest
@QuarkusTestResource(MongoDbInitResource.class)
@TestSecurity(user = "system@system.com", roles = {"admin"})
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

   @AfterEach
   void cleanUp() {
      collection("userGroup").deleteMany(Filters.in("_id", groups));
      collection("policy").deleteMany(Filters.in("_id", policies));
      unwrap().mode = CanonicalizeRefNames.REPORT;
   }

   @Test
   void renamesMergesAndReportsConflictsWithoutChangingGrants() throws Exception {
      // RENAME: no canonical record yet; members stay where they are.
      ObjectId renamed = legacyGroup("it-ops-" + run, List.of("ops"), "ann@it.com");
      ObjectId renamedPolicy = legacyPolicy("it-ops-policy-" + run, "ops", RuleEffect.ALLOW);
      // MERGE: an equivalent canonical group and policy exist.
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

      Map<ObjectId, Action> plan = plan();
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
      assertTrue(plan.values().stream().noneMatch(action -> "tenant-admin-users".equals(action.from())
            || "defaultAnonymousPolicy".equals(action.from())), "declared legacy names are left alone");

      // Report mode (the default) changes nothing.
      execute();
      assertEquals("it-ops-" + run, refName("userGroup", renamed));
      assertEquals(plan.keySet(), plan().keySet());

      // Apply.
      unwrap().mode = CanonicalizeRefNames.APPLY;
      execute();
      assertEquals("IT_OPS_" + run, refName("userGroup", renamed));
      assertEquals("IT_OPS_POLICY_" + run, refName("policy", renamedPolicy));
      assertTrue(memberOf("ann@it.com").contains("IT_OPS_" + run), "membership survives the rename");
      assertNull(refName("userGroup", duplicateGroup));
      assertNull(refName("policy", duplicatePolicy));
      assertEquals("IT_DUP_POLICY_" + run, refName("policy", canonicalPolicy));
      assertTrue(memberOf("cid@it.com").contains("IT_DUP_" + run), "merged members join the canonical group");
      assertTrue(memberOf("bob@it.com").contains("IT_DUP_" + run));
      assertEquals("it-diff-" + run, refName("userGroup", differingGroup));
      assertEquals("it-diff-policy-" + run, refName("policy", differingPolicy));
      assertEquals("it-amb-" + run, refName("userGroup", ambiguousA));
      assertEquals("9-it-" + run, refName("userGroup", leadingDigit));

      // Re-running only reports what an operator must still decide.
      Map<ObjectId, Action> remaining = plan();
      assertEquals(List.of(Kind.CONFLICT, Kind.CONFLICT, Kind.CONFLICT, Kind.CONFLICT, Kind.UNRESOLVABLE),
            List.of(remaining.get(differingGroup).kind(), remaining.get(differingPolicy).kind(),
                  remaining.get(ambiguousA).kind(), remaining.get(ambiguousB).kind(),
                  remaining.get(leadingDigit).kind()));
      assertEquals(5, remaining.size());
      execute();
      assertEquals("IT_OPS_" + run, refName("userGroup", renamed));
   }

   @Test
   void reportEndpointReturnsThePlanWithoutChangingAnything() {
      ObjectId planted = legacyGroup("it-rest-" + run, List.of("rest"), null);
      io.restassured.RestAssured.given()
            .when().get("/system/migration/refnames/" + REALM)
            .then().statusCode(200)
            .body("realm", org.hamcrest.Matchers.is(REALM))
            .body("actions.find { it.id == '" + planted.toHexString() + "' }.kind", org.hamcrest.Matchers.is("RENAME"))
            .body("actions.find { it.id == '" + planted.toHexString() + "' }.to",
                  org.hamcrest.Matchers.is("IT_REST_" + run));
      assertEquals("it-rest-" + run, refName("userGroup", planted));
      io.restassured.RestAssured.given()
            .when().get("/system/migration/refnames/no-such-realm-" + run)
            .then().statusCode(404);
   }

   private CanonicalizeRefNames unwrap() {
      return ClientProxy.unwrap(changeSet);
   }

   /** Runs the change set the way MigrationService does: in a transaction on the realm's session. */
   private void execute() throws Exception {
      try (MorphiaSession session = morphiaDataStoreWrapper.getDataStore(REALM).startSession()) {
         session.startTransaction();
         changeSet.execute(session, mongoClient, emitter());
         session.commitTransaction();
      }
   }

   /** This run's actions, keyed by record id. */
   private Map<ObjectId, Action> plan() {
      RefNameMigrationPlan plan = migrator.plan(morphiaDataStoreWrapper.getDataStore(REALM));
      return plan.actions().stream()
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
      collection("userGroup").updateOne(Filters.eq("_id", id), Updates.set("refName", legacyName));
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
      collection("policy").updateOne(Filters.eq("_id", id), Updates.set("refName", legacyName));
      return id;
   }

   private String refName(String collection, ObjectId id) {
      Document row = collection(collection).find(Filters.eq("_id", id)).first();
      return row == null ? null : row.getString("refName");
   }

   private List<String> memberOf(String member) {
      EntityReference profile = new EntityReference();
      profile.setEntityRefName(member);
      profile.setEntityDisplayName(member);
      return userGroupRepo.findByUserProfileRefWithIgnoreRules(REALM, profile).stream()
            .map(UserGroup::getRefName).toList();
   }

   private MongoCollection<Document> collection(String name) {
      return mongoClient.getDatabase(REALM).getCollection(name);
   }

   @SuppressWarnings("unchecked")
   private static MultiEmitter<? super String> emitter() {
      return (MultiEmitter<? super String>) Proxy.newProxyInstance(CanonicalizeRefNamesIT.class.getClassLoader(),
            new Class<?>[]{MultiEmitter.class},
            (proxy, method, args) -> "emit".equals(method.getName()) ? proxy : null);
   }
}

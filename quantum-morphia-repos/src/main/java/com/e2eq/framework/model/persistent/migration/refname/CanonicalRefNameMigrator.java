package com.e2eq.framework.model.persistent.migration.refname;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.RefNameRule;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Action;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Kind;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.RefNameContract;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.UserGroup;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.DeleteOptions;
import dev.morphia.UpdateOptions;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Brings stored refNames of {@code @CanonicalRefName} models (UserGroup, Policy) in line with {@link RefNameRule}.
 *
 * <p>{@link RefNameContract} validates refNames on create only and makes them immutable, so records written before
 * the rule keep their old spelling, and a seed pack that now uses the canonical name creates a second record beside
 * the old one instead of updating it. This migrator plans one {@link Action} per such record:</p>
 * <ul>
 *    <li>{@link Kind#RENAME}: nothing in the record's data domain holds the canonical name, so it is renamed in place.
 *    Grants do not move: policies match on principalId and membership is resolved through the group's userProfiles,
 *    never by refName.</li>
 *    <li>{@link Kind#MERGE}: an equivalent canonical record exists in the same data domain (a group with the same
 *    application, status and roles; a policy with the same principal, scope, status and rules). A group's members are
 *    added to it; then the old record is removed.</li>
 *    <li>{@link Kind#CONFLICT} and {@link Kind#UNRESOLVABLE}: reported, never changed. Folding a differing record
 *    would silently grant or revoke access, so that stays an operator decision.</li>
 * </ul>
 * <p>Names are compared the way the refName index compares them: per data domain (org, tenant, owner) and without
 * regard to case. Declared legacy names ({@link RefNameContract#legacyNames}) are never renamed, but they occupy their
 * name. The canonical form replaces each run of characters outside {@code [A-Za-z0-9]} with {@code _}, trims leading
 * and trailing underscores and upper-cases the result ({@code digital-worker-operator} becomes
 * {@code DIGITAL_WORKER_OPERATOR}).</p>
 *
 * <p>Planning reads the realm's collections directly, so it sees system-plane types that a tenant realm's datastore
 * leaves unmapped and creates nothing in the database. Applying uses Morphia operations in the caller's session and
 * runs no repository hooks: ontology edges derived from a merged group are not re-materialized for its new members,
 * and the removed record's edges are not cleaned up. Each write is guarded by the refName it was planned against, but
 * a MERGE does not re-compare the canonical twin, so apply a fresh plan.</p>
 */
@ApplicationScoped
public class CanonicalRefNameMigrator {

   static final String MIGRATION_IDENTITY = "quantum-refname-migration";

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   @Inject
   MongoClient mongoClient;

   @Inject
   @Any
   Instance<BaseMorphiaRepo<?>> repositories;

   /** The canonical spelling of {@code refName}, or null when none satisfies {@link RefNameRule}. */
   public static String canonicalForm(String refName) {
      if (refName == null) {
         return null;
      }
      String candidate = refName.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
      return RefNameRule.check(candidate).isEmpty() ? candidate : null;
   }

   /** Computes the plan for {@code realm}. Reads only; never creates a datastore, collection or index. */
   public RefNameMigrationPlan plan(String realm) {
      MongoDatabase database = mongoClient.getDatabase(realm);
      Set<String> collections = database.listCollectionNames().into(new LinkedHashSet<>());
      List<Action> actions = new ArrayList<>();
      for (Map.Entry<String, Class<?>> type : canonicalTypes().entrySet()) {
         if (collections.contains(type.getKey())) {
            actions.addAll(plan(type.getValue(), type.getKey(),
                  database.getCollection(type.getKey()).find().into(new ArrayList<>())));
         }
      }
      return new RefNameMigrationPlan(realm, List.copyOf(actions));
   }

   /**
    * {@code @CanonicalRefName} types by collection, found through their repository beans. Fails rather than plan
    * without one: a type that silently dropped out would report and apply nothing for it.
    */
   private Map<String, Class<?>> canonicalTypes() {
      Map<String, Class<?>> types = new LinkedHashMap<>();
      for (BaseMorphiaRepo<?> repo : repositories) {
         Class<?> type = repo.getPersistentClass();
         if (type != null && RefNameContract.applies(type)) {
            types.putIfAbsent(morphiaDataStoreWrapper.collectionNameOf(type), type);
         }
      }
      for (Class<?> required : List.of(Policy.class, UserGroup.class)) {
         if (!types.containsValue(required)) {
            throw new IllegalStateException("refName migration: no repository for " + required.getSimpleName());
         }
      }
      return types;
   }

   private List<Action> plan(Class<?> type, String collection, List<Document> rows) {
      Set<String> legacy = RefNameContract.legacyNames(type);
      // Every stored name occupies its (data domain, upper-cased name) slot, as in the unique refName index.
      Map<String, List<Document>> occupants = new HashMap<>();
      Map<String, Integer> claims = new HashMap<>();
      List<Document> offending = new ArrayList<>();
      for (Document row : rows) {
         String refName = row.getString("refName");
         if (refName == null || refName.isEmpty()) {
            continue;
         }
         occupants.computeIfAbsent(slot(row, refName), k -> new ArrayList<>()).add(row);
         if (RefNameRule.check(refName).isPresent() && !legacy.contains(refName)) {
            offending.add(row);
            String target = canonicalForm(refName);
            if (target != null) {
               claims.merge(slot(row, target), 1, Integer::sum);
            }
         }
      }

      List<Action> actions = new ArrayList<>();
      for (Document row : offending) {
         String from = row.getString("refName");
         String to = canonicalForm(from);
         String id = String.valueOf(row.get("_id"));
         String entity = type.getSimpleName();
         if (to == null) {
            actions.add(new Action(entity, collection, id, from, null, null, Kind.UNRESOLVABLE,
                  "no canonical spelling satisfies " + RefNameRule.DESCRIPTION));
            continue;
         }
         String slot = slot(row, to);
         List<Document> holders = occupants.getOrDefault(slot, List.of()).stream()
               .filter(other -> !Objects.equals(other.get("_id"), row.get("_id")))
               .toList();
         Kind kind;
         String reason = null;
         String target = holders.size() == 1 ? String.valueOf(holders.get(0).get("_id")) : null;
         if (claims.getOrDefault(slot, 0) > 1) {
            kind = Kind.CONFLICT;
            reason = claims.get(slot) + " records in this data domain map to " + to;
         } else if (holders.isEmpty()) {
            kind = Kind.RENAME;
         } else if (holders.size() > 1) {
            kind = Kind.CONFLICT;
            reason = holders.size() + " records in this data domain already hold " + to;
         } else if (!to.equals(holders.get(0).getString("refName"))) {
            kind = Kind.CONFLICT;
            reason = "the name is held as '" + holders.get(0).getString("refName") + "' (declared legacy)";
         } else {
            reason = differences(type, row, holders.get(0));
            kind = reason == null ? Kind.MERGE : Kind.CONFLICT;
         }
         actions.add(new Action(entity, collection, id, from, to, target, kind, reason));
      }
      return actions;
   }

   private static String slot(Document row, String refName) {
      Document domain = row.get("dataDomain", Document.class);
      String key = domain == null ? "||" : String.join("|", text(domain.get("orgRefName")),
            text(domain.get("tenantId")), text(domain.get("ownerId")));
      return key + "|" + refName.toUpperCase(Locale.ROOT);
   }

   private static String text(Object value) {
      return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
   }

   /** Why {@code row} cannot be folded into {@code target}, or null when it can. */
   private static String differences(Class<?> type, Document row, Document target) {
      List<String> fields;
      if (UserGroup.class.isAssignableFrom(type)) {
         fields = List.of("applicationId", "activeStatus", "roles");
      } else if (Policy.class.isAssignableFrom(type)) {
         fields = List.of("principalId", "principalType", "applicationId", "realmRefName", "activeStatus", "rules");
      } else {
         return "no merge strategy for " + type.getSimpleName();
      }
      List<String> differing = new ArrayList<>();
      for (String field : fields) {
         Object left = "roles".equals(field) ? asSet(row.get(field)) : row.get(field);
         Object right = "roles".equals(field) ? asSet(target.get(field)) : target.get(field);
         if (!Objects.equals(left, right)) {
            differing.add(field);
         }
      }
      return differing.isEmpty() ? null : "differs from the canonical record in " + String.join(", ", differing);
   }

   private static Set<Object> asSet(Object value) {
      return value instanceof Collection<?> values ? new LinkedHashSet<>(values) : Set.of();
   }

   /**
    * Applies the {@link Kind#RENAME} and {@link Kind#MERGE} actions of {@code plan} in {@code session}, which must
    * belong to the plan's realm and should be in a transaction; conflicts and unresolvable names are only reported.
    * Each write is guarded by the state it was planned against and fails when that state has changed.
    */
   public void apply(MorphiaSession session, RefNameMigrationPlan plan, Consumer<String> log) {
      if (!session.getDatabase().getName().equals(plan.realm())) {
         throw new IllegalArgumentException("plan for " + plan.realm() + " cannot be applied to "
               + session.getDatabase().getName());
      }
      Map<String, Class<?>> types = canonicalTypes();
      for (Action action : plan.actions()) {
         switch (action.kind()) {
            case RENAME -> {
               rename(session, type(types, action), action);
               log.accept("Applied: " + describe(action));
            }
            case MERGE -> {
               merge(session, type(types, action), action);
               log.accept("Applied: " + describe(action));
            }
            case CONFLICT, UNRESOLVABLE -> log.accept("Left for an operator: " + describe(action));
         }
      }
   }

   private static String describe(Action action) {
      return new RefNameMigrationPlan(null, List.of(action)).describe().get(0);
   }

   private static Class<?> type(Map<String, Class<?>> types, Action action) {
      Class<?> type = types.get(action.collection());
      if (type == null) {
         throw new IllegalStateException("refName migration: no @CanonicalRefName type for collection "
               + action.collection());
      }
      return type;
   }

   private void rename(MorphiaSession session, Class<?> type, Action action) {
      UpdateResult result = byId(session, type, action.id())
            .filter(Filters.eq("refName", action.from()))
            .update(new UpdateOptions(),
                  UpdateOperators.set("refName", action.to()),
                  UpdateOperators.set("auditInfo.lastUpdateTs", new Date()),
                  UpdateOperators.set("auditInfo.lastUpdateIdentity", MIGRATION_IDENTITY));
      requireOne(result.getMatchedCount(), action, "holds '" + action.from() + "'");
   }

   private void merge(MorphiaSession session, Class<?> type, Action action) {
      if (UserGroup.class.isAssignableFrom(type)) {
         UserGroup old = byId(session, UserGroup.class, action.id()).first();
         UserGroup target = byId(session, UserGroup.class, action.targetId()).first();
         if (old == null || target == null) {
            requireOne(0, action, "exists with its canonical twin");
         }
         List<EntityReference> members = new ArrayList<>(nullSafe(target.getUserProfiles()));
         Set<String> present = new LinkedHashSet<>();
         members.forEach(member -> present.add(memberKey(member)));
         for (EntityReference member : nullSafe(old.getUserProfiles())) {
            if (present.add(memberKey(member))) {
               members.add(member);
            }
         }
         if (members.size() != nullSafe(target.getUserProfiles()).size()) {
            byId(session, UserGroup.class, action.targetId())
                  .update(new UpdateOptions(),
                        UpdateOperators.set("userProfiles", members),
                        UpdateOperators.set("auditInfo.lastUpdateTs", new Date()),
                        UpdateOperators.set("auditInfo.lastUpdateIdentity", MIGRATION_IDENTITY));
         }
      } else if (!Policy.class.isAssignableFrom(type)) {
         throw new IllegalStateException("refName migration: no merge strategy for " + action.entity());
      }
      DeleteResult deleted = byId(session, type, action.id())
            .filter(Filters.eq("refName", action.from()))
            .delete(new DeleteOptions());
      requireOne(deleted.getDeletedCount(), action, "holds '" + action.from() + "'");
   }

   /** Matches an id stored as an ObjectId or as a string. */
   private static <T> Query<T> byId(MorphiaSession session, Class<T> type, String id) {
      List<Object> ids = new ArrayList<>(List.of(id));
      if (ObjectId.isValid(id)) {
         ids.add(new ObjectId(id));
      }
      return session.find(type).filter(Filters.in("_id", ids));
   }

   private static void requireOne(long count, Action action, String expectation) {
      if (count != 1) {
         throw new IllegalStateException("refName migration: " + action.entity() + " " + action.id()
               + " no longer " + expectation + "; recompute the plan");
      }
   }

   private static List<EntityReference> nullSafe(List<EntityReference> values) {
      return values == null ? List.of() : values;
   }

   /** Group membership is looked up by the profile's refName, so that is what identifies a member. */
   private static String memberKey(EntityReference member) {
      return member.getEntityRefName() != null ? "ref:" + member.getEntityRefName()
            : "id:" + member.getEntityId();
   }
}

package com.e2eq.framework.model.persistent.migration.refname;

import com.e2eq.framework.model.persistent.base.RefNameRule;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Action;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Kind;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.RefNameContract;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.UserGroup;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import dev.morphia.transactions.MorphiaSession;
import dev.morphia.transactions.SessionDatastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings stored refNames of {@code @CanonicalRefName} models (UserGroup, Policy) in line with {@link RefNameRule}.
 *
 * <p>{@link RefNameContract} validates refNames on create only and makes them immutable, so records written before
 * the rule keep their old spelling, and a seed pack that now uses the canonical name creates a second record beside
 * the old one instead of updating it. This migrator plans one {@link Action} per such record:</p>
 * <ul>
 *    <li>{@link Kind#RENAME}: nothing in the record's data domain holds the canonical name, so it is renamed in place.
 *    Grants do not move: policies match on principalId, membership is resolved through the group's userProfiles, and
 *    materialized ontology edges key the group by id.</li>
 *    <li>{@link Kind#MERGE}: an equivalent canonical record exists in the same data domain (a group with the same
 *    application, status and roles; a policy with the same principal, scope, status and rules). A group's members are
 *    added to it, every {@link RefNameMergeParticipant} moves what references the old record onto it, and the old
 *    record is removed.</li>
 *    <li>{@link Kind#CONFLICT} and {@link Kind#UNRESOLVABLE}: reported, never changed. Folding a differing record
 *    would silently grant or revoke access, so that stays an operator decision. A record that a participant reports
 *    as referenced by refName (rather than id) is a conflict too.</li>
 * </ul>
 * <p>Names are compared the way the refName index compares them: within one data domain (org, tenant, owner, each
 * compared without case, null distinct from empty) and without regard to case. Declared legacy names
 * ({@link RefNameContract#legacyNames}) are never renamed, but they occupy their name. The canonical form replaces each
 * run of characters outside {@code [A-Za-z0-9]} with {@code _}, trims leading and trailing underscores and upper-cases
 * the result ({@code digital-worker-operator} becomes {@code DIGITAL_WORKER_OPERATOR}).</p>
 *
 * <p>Planning reads the realm's collections directly, so it sees system-plane types that a tenant realm's datastore
 * leaves unmapped and creates nothing in the database. {@link #apply} re-plans inside the transaction and refuses to
 * write unless every action it is asked to apply is still exactly what the reviewed plan said.</p>
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

   @Inject
   @Any
   Instance<RefNameMergeParticipant> participants;

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
            actions.addAll(plan(database, type.getValue(), type.getKey(),
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

   private List<Action> plan(MongoDatabase database, Class<?> type, String collection, List<Document> rows) {
      Set<String> legacy = RefNameContract.legacyNames(type);
      String entity = type.getSimpleName();
      // Every stored name occupies its (data domain, upper-cased name) slot, as in the unique refName index.
      Map<List<Object>, List<Document>> occupants = new HashMap<>();
      Map<List<Object>, Integer> claims = new HashMap<>();
      List<Document> offending = new ArrayList<>();
      List<Action> actions = new ArrayList<>();
      for (Document row : rows) {
         Object value = row.get("refName");
         String refName = value instanceof String text ? text : null;
         if (refName == null || refName.isEmpty()) {
            actions.add(new Action(entity, collection, id(row), refName, null, null, Kind.UNRESOLVABLE,
                  "the record has no refName; name it once under the rule"));
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

      for (Document row : offending) {
         String from = row.getString("refName");
         String to = canonicalForm(from);
         String id = id(row);
         if (to == null) {
            actions.add(new Action(entity, collection, id, from, null, null, Kind.UNRESOLVABLE,
                  "no canonical spelling satisfies " + RefNameRule.DESCRIPTION));
            continue;
         }
         List<Object> slot = slot(row, to);
         List<Document> holders = occupants.getOrDefault(slot, List.of()).stream()
               .filter(other -> !Objects.equals(other.get("_id"), row.get("_id")))
               .toList();
         Kind kind;
         String reason = null;
         String target = holders.size() == 1 ? id(holders.get(0)) : null;
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
            if (reason != null) {
               kind = Kind.CONFLICT;
            } else if (participants.isUnsatisfied() && !asList(row.get("userProfiles")).isEmpty()) {
               kind = Kind.CONFLICT;
               reason = "its members would move, but no merge participant is registered to move their derived "
                     + "references (ontology edges)";
            } else {
               kind = Kind.MERGE;
               long references = 0;
               for (RefNameMergeParticipant participant : participants) {
                  references += participant.references(database, id);
               }
               reason = references == 0 ? null : references + " derived reference(s) move to the canonical record";
            }
         }
         if (kind == Kind.RENAME || kind == Kind.MERGE) {
            long byName = 0;
            for (RefNameMergeParticipant participant : participants) {
               byName += participant.referencesByName(database, entity, from);
            }
            if (byName > 0) {
               kind = Kind.CONFLICT;
               reason = byName + " derived reference(s) name '" + from + "' instead of its id; the migration does "
                     + "not rewrite them";
            }
         }
         actions.add(new Action(entity, collection, id, from, to, target, kind, reason));
      }
      return actions;
   }

   private static String id(Document row) {
      return String.valueOf(row.get("_id"));
   }

   /** The index slot of {@code refName} in {@code row}'s data domain; null and empty stay distinct. */
   private static List<Object> slot(Document row, String refName) {
      Document domain = row.get("dataDomain") instanceof Document value ? value : new Document();
      return Arrays.asList(fold(domain.get("orgRefName")), fold(domain.get("tenantId")), fold(domain.get("ownerId")),
            refName.toUpperCase(Locale.ROOT));
   }

   private static Object fold(Object value) {
      return value instanceof String text ? text.toLowerCase(Locale.ROOT) : value;
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
         Object left = "roles".equals(field) ? new LinkedHashSet<>(asList(row.get(field))) : row.get(field);
         Object right = "roles".equals(field) ? new LinkedHashSet<>(asList(target.get(field))) : target.get(field);
         if (!Objects.equals(left, right)) {
            differing.add(field);
         }
      }
      return differing.isEmpty() ? null : "differs from the canonical record in " + String.join(", ", differing);
   }

   private static List<Object> asList(Object value) {
      return value instanceof Collection<?> values ? new ArrayList<>(values) : List.of();
   }

   /**
    * Applies the {@link Kind#RENAME} and {@link Kind#MERGE} actions of a reviewed {@code plan} in {@code session},
    * which must belong to the plan's realm and be in a transaction. The affected collections are re-planned inside the
    * transaction first; if any action to apply differs from the reviewed one (another record took the name, the
    * canonical twin changed or vanished), nothing is written and the call fails. Each write is also guarded by the
    * state it expects, and a merge writes the canonical record too, so a concurrent change to it aborts the commit.
    */
   public void apply(MorphiaSession session, RefNameMigrationPlan plan, Consumer<String> log) {
      if (!session.getDatabase().getName().equals(plan.realm())) {
         throw new IllegalArgumentException("plan for " + plan.realm() + " cannot be applied to "
               + session.getDatabase().getName());
      }
      if (!(session instanceof SessionDatastore datastore) || !session.hasActiveTransaction()) {
         throw new IllegalStateException("refName migration: apply needs a Morphia session in a transaction");
      }
      ClientSession client = datastore.getSession();
      MongoDatabase database = mongoClient.getDatabase(plan.realm());
      Map<String, Class<?>> types = canonicalTypes();

      List<Action> writes = plan.actions().stream()
            .filter(action -> action.kind() == Kind.RENAME || action.kind() == Kind.MERGE)
            .toList();
      Map<String, Map<String, Document>> rows = new HashMap<>();
      for (String collection : writes.stream().map(Action::collection).collect(Collectors.toCollection(LinkedHashSet::new))) {
         Class<?> type = types.get(collection);
         if (type == null) {
            throw new IllegalStateException("refName migration: no @CanonicalRefName type for collection " + collection);
         }
         List<Document> current = database.getCollection(collection).find(client).into(new ArrayList<>());
         Map<String, Action> replanned = plan(database, type, collection, current).stream()
               .collect(Collectors.toMap(Action::id, Function.identity(), (left, right) -> left));
         for (Action action : writes) {
            if (action.collection().equals(collection) && !sameDecision(action, replanned.get(action.id()))) {
               throw new IllegalStateException("refName migration: the plan for " + action.entity() + " "
                     + action.id() + " ('" + action.from() + "') changed since it was reviewed; review the plan again");
            }
         }
         rows.put(collection, current.stream().collect(Collectors.toMap(CanonicalRefNameMigrator::id, row -> row)));
      }

      for (Action action : plan.actions()) {
         switch (action.kind()) {
            case RENAME -> {
               rename(client, database, rows.get(action.collection()).get(action.id()), action);
               log.accept("Applied: " + describe(action));
            }
            case MERGE -> {
               Map<String, Document> collectionRows = rows.get(action.collection());
               merge(client, database, types.get(action.collection()), collectionRows.get(action.id()),
                     collectionRows.get(action.targetId()), action);
               log.accept("Applied: " + describe(action));
            }
            case CONFLICT, UNRESOLVABLE -> log.accept("Left for an operator: " + describe(action));
         }
      }
   }

   private static boolean sameDecision(Action reviewed, Action current) {
      return current != null && current.kind() == reviewed.kind() && Objects.equals(current.from(), reviewed.from())
            && Objects.equals(current.to(), reviewed.to()) && Objects.equals(current.targetId(), reviewed.targetId());
   }

   private static String describe(Action action) {
      return new RefNameMigrationPlan(null, List.of(action)).describe().get(0);
   }

   private static Bson stamped(Bson update) {
      return Updates.combine(update,
            Updates.inc("version", 1L),
            Updates.set("auditInfo.lastUpdateTs", new Date()),
            Updates.set("auditInfo.lastUpdateIdentity", MIGRATION_IDENTITY));
   }

   private static void rename(ClientSession client, MongoDatabase database, Document row, Action action) {
      long matched = database.getCollection(action.collection()).updateOne(client,
            Filters.and(Filters.eq("_id", row.get("_id")), Filters.eq("refName", action.from())),
            stamped(Updates.set("refName", action.to()))).getMatchedCount();
      requireOne(matched, action, "holds '" + action.from() + "'");
   }

   private void merge(ClientSession client, MongoDatabase database, Class<?> type, Document old, Document target,
                      Action action) {
      MongoCollection<Document> collection = database.getCollection(action.collection());
      Bson targetUpdate;
      if (UserGroup.class.isAssignableFrom(type)) {
         List<Object> members = asList(target.get("userProfiles"));
         Set<String> present = new LinkedHashSet<>();
         members.forEach(member -> present.add(memberKey(member)));
         for (Object member : asList(old.get("userProfiles"))) {
            if (present.add(memberKey(member))) {
               members.add(member);
            }
         }
         targetUpdate = stamped(Updates.set("userProfiles", members));
      } else if (Policy.class.isAssignableFrom(type)) {
         // Nothing to fold into a policy, but writing it ties this merge to the state the plan was checked against.
         targetUpdate = stamped(Updates.set("refName", action.to()));
      } else {
         throw new IllegalStateException("refName migration: no merge strategy for " + action.entity());
      }
      long matched = collection.updateOne(client,
            Filters.and(Filters.eq("_id", target.get("_id")), Filters.eq("refName", action.to())), targetUpdate)
            .getMatchedCount();
      requireOne(matched, action, "has its canonical twin " + action.targetId());
      for (RefNameMergeParticipant participant : participants) {
         participant.merge(client, database, action.id(), action.targetId());
      }
      long deleted = collection.deleteOne(client,
            Filters.and(Filters.eq("_id", old.get("_id")), Filters.eq("refName", action.from()))).getDeletedCount();
      requireOne(deleted, action, "holds '" + action.from() + "'");
   }

   private static void requireOne(long count, Action action, String expectation) {
      if (count != 1) {
         throw new IllegalStateException("refName migration: " + action.entity() + " " + action.id()
               + " no longer " + expectation + "; review the plan again");
      }
   }

   /** Group membership is looked up by the profile's refName, so that is what identifies a member. */
   private static String memberKey(Object member) {
      if (member instanceof Document reference) {
         Object refName = reference.get("entityRefName");
         return refName != null ? "ref:" + refName : "id:" + reference.get("entityId");
      }
      return "raw:" + member;
   }
}

package com.e2eq.framework.model.persistent.migration.refname;

import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.RefNameRule;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Action;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Kind;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.persistent.morphia.RefNameContract;
import com.e2eq.framework.model.persistent.morphia.UserGroupRepo;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.UserGroup;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import dev.morphia.UpdateOptions;
import dev.morphia.query.updates.UpdateOperators;
import dev.morphia.MorphiaDatastore;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.transactions.MorphiaSession;
import jakarta.enterprise.context.ApplicationScoped;
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
 * the old one instead of updating it. This migrator plans one {@link Action} per such record and applies the safe
 * ones:</p>
 * <ul>
 *    <li>{@link Kind#RENAME}: no record holds the canonical name, so the record is renamed in place. Grants do not
 *    move: policies match on principalId and membership is resolved through the group's userProfiles, never by
 *    refName.</li>
 *    <li>{@link Kind#MERGE}: an equivalent canonical record exists (a group with the same application and roles, a
 *    policy with the same principal, scope and rules). A group's members are added to it; then the old record is
 *    removed through its repository.</li>
 *    <li>{@link Kind#CONFLICT} and {@link Kind#UNRESOLVABLE}: reported, never changed. Folding a differing record
 *    would silently grant or revoke access, so that stays an operator decision.</li>
 * </ul>
 * <p>Declared legacy names ({@link RefNameContract#legacyNames}) are left alone: framework code looks them up by
 * that spelling. The canonical form replaces each run of characters outside {@code [A-Za-z0-9]} with {@code _},
 * trims leading and trailing underscores and upper-cases the result ({@code digital-worker-operator} becomes
 * {@code DIGITAL_WORKER_OPERATOR}).</p>
 */
@ApplicationScoped
public class CanonicalRefNameMigrator {

   static final String MIGRATION_IDENTITY = "quantum-refname-migration";

   @Inject
   UserGroupRepo userGroupRepo;

   @Inject
   PolicyRepo policyRepo;

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   /** The canonical spelling of {@code refName}, or null when none satisfies {@link RefNameRule}. */
   public static String canonicalForm(String refName) {
      if (refName == null) {
         return null;
      }
      String candidate = refName.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").toUpperCase(Locale.ROOT);
      return RefNameRule.check(candidate).isEmpty() ? candidate : null;
   }

   /** Computes the plan for the realm behind {@code datastore}. Reads only. */
   public RefNameMigrationPlan plan(MorphiaDatastore datastore) {
      List<Action> actions = new ArrayList<>();
      for (EntityModel model : datastore.getMapper().getMappedEntities()) {
         Class<?> type = model.getType();
         if (RefNameContract.applies(type)) {
            actions.addAll(plan(type, datastore.getDatabase().getCollection(model.collectionName())));
         }
      }
      return new RefNameMigrationPlan(datastore.getDatabase().getName(), List.copyOf(actions));
   }

   private List<Action> plan(Class<?> type, MongoCollection<Document> collection) {
      List<Document> rows = collection.find().into(new ArrayList<>());
      Set<String> legacy = RefNameContract.legacyNames(type);
      // Canonical records by upper-cased name: the refName index compares case-insensitively.
      Map<String, List<Document>> canonical = new HashMap<>();
      List<Document> offending = new ArrayList<>();
      for (Document row : rows) {
         String refName = row.getString("refName");
         if (refName == null || refName.isEmpty()) {
            continue;
         }
         if (RefNameRule.check(refName).isEmpty()) {
            canonical.computeIfAbsent(refName.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(row);
         } else if (!legacy.contains(refName)) {
            offending.add(row);
         }
      }
      Map<String, Integer> claims = new HashMap<>();
      for (Document row : offending) {
         String target = canonicalForm(row.getString("refName"));
         if (target != null) {
            claims.merge(target, 1, Integer::sum);
         }
      }

      List<Action> actions = new ArrayList<>();
      for (Document row : offending) {
         String from = row.getString("refName");
         String to = canonicalForm(from);
         String id = String.valueOf(row.get("_id"));
         String entity = type.getSimpleName();
         if (to == null) {
            actions.add(new Action(entity, collection.getNamespace().getCollectionName(), id, from, null, null,
                  Kind.UNRESOLVABLE, "no canonical spelling satisfies " + RefNameRule.DESCRIPTION));
            continue;
         }
         List<Document> existing = canonical.getOrDefault(to, List.of());
         String target = existing.size() == 1 ? String.valueOf(existing.get(0).get("_id")) : null;
         Kind kind;
         String reason = null;
         if (claims.get(to) > 1) {
            kind = Kind.CONFLICT;
            reason = claims.get(to) + " records map to " + to;
         } else if (existing.isEmpty()) {
            kind = Kind.RENAME;
         } else if (existing.size() > 1) {
            kind = Kind.CONFLICT;
            reason = existing.size() + " records already hold " + to;
         } else {
            reason = differences(type, row, existing.get(0));
            kind = reason == null ? Kind.MERGE : Kind.CONFLICT;
         }
         actions.add(new Action(entity, collection.getNamespace().getCollectionName(), id, from, to, target, kind,
               reason));
      }
      return actions;
   }

   /** Why {@code row} cannot be folded into {@code target}, or null when it can. */
   private static String differences(Class<?> type, Document row, Document target) {
      List<String> differing = new ArrayList<>();
      if (UserGroup.class.isAssignableFrom(type)) {
         compare(differing, "applicationId", row.get("applicationId"), target.get("applicationId"));
         compare(differing, "roles", asSet(row.get("roles")), asSet(target.get("roles")));
      } else if (Policy.class.isAssignableFrom(type)) {
         for (String field : List.of("principalId", "principalType", "applicationId", "realmRefName", "rules")) {
            compare(differing, field, row.get(field), target.get(field));
         }
      } else {
         return "no merge strategy for " + type.getSimpleName();
      }
      return differing.isEmpty() ? null : "differs from the canonical record in " + String.join(", ", differing);
   }

   private static void compare(List<String> differing, String field, Object left, Object right) {
      if (!Objects.equals(left, right)) {
         differing.add(field);
      }
   }

   private static Set<Object> asSet(Object value) {
      return value instanceof Collection<?> values ? new LinkedHashSet<>(values) : Set.of();
   }

   /**
    * Applies the {@link Kind#RENAME} and {@link Kind#MERGE} actions of a plan computed in this session; conflicts
    * and unresolvable names are only reported. Renames are written with a plain Morphia update in the migration
    * session (the repositories refuse a refName change by design) and only while the record still carries the planned name.
    */
   public void apply(MorphiaSession session, RefNameMigrationPlan plan, Consumer<String> log) {
      for (Action action : plan.actions()) {
         switch (action.kind()) {
            case RENAME -> rename(session, action);
            case MERGE -> merge(session, action);
            case CONFLICT, UNRESOLVABLE -> log.accept("Left for an operator: " + describe(action));
         }
         if (action.kind() == Kind.RENAME || action.kind() == Kind.MERGE) {
            log.accept("Applied: " + describe(action));
         }
      }
   }

   private static String describe(Action action) {
      return new RefNameMigrationPlan(null, List.of(action)).describe().get(0);
   }

   private void rename(MorphiaSession session, Action action) {
      Class<?> type = morphiaDataStoreWrapper.getDataStore(session.getDatabase().getName()).getMapper()
            .getMappedEntities().stream()
            .filter(model -> model.collectionName().equals(action.collection()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("refName migration: no mapped entity for collection "
                  + action.collection()))
            .getType();
      UpdateResult result = session.find(type)
            .filter(dev.morphia.query.filters.Filters.eq("_id", id(action.id())),
                  dev.morphia.query.filters.Filters.eq("refName", action.from()))
            .update(new UpdateOptions(),
                  UpdateOperators.set("refName", action.to()),
                  UpdateOperators.set("auditInfo.lastUpdateTs", new Date()),
                  UpdateOperators.set("auditInfo.lastUpdateIdentity", MIGRATION_IDENTITY));
      if (result.getMatchedCount() != 1) {
         throw new IllegalStateException("refName migration: " + action.entity() + " " + action.id() + " no longer "
               + "holds '" + action.from() + "'; recompute the plan");
      }
   }

   private void merge(MorphiaSession session, Action action) {
      try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
         if ("UserGroup".equals(action.entity())) {
            UserGroup old = load(session, UserGroup.class, action.id());
            UserGroup target = load(session, UserGroup.class, action.targetId());
            Map<String, EntityReference> members = new LinkedHashMap<>();
            for (List<EntityReference> profiles : List.of(nullSafe(target.getUserProfiles()), nullSafe(old.getUserProfiles()))) {
               for (EntityReference profile : profiles) {
                  members.putIfAbsent(memberKey(profile), profile);
               }
            }
            if (members.size() != nullSafe(target.getUserProfiles()).size()) {
               target.setUserProfiles(new ArrayList<>(members.values()));
               userGroupRepo.save(session, target);
            }
            userGroupRepo.delete(session, old);
         } else if ("Policy".equals(action.entity())) {
            policyRepo.delete(session, load(session, Policy.class, action.id()));
         } else {
            throw new IllegalStateException("refName migration: no merge strategy for " + action.entity());
         }
      } catch (com.e2eq.framework.exceptions.ReferentialIntegrityViolationException e) {
         throw new IllegalStateException("refName migration: cannot remove " + action.entity() + " '" + action.from()
               + "': " + e.getMessage(), e);
      }
   }

   private static <T> T load(MorphiaSession session, Class<T> type, String id) {
      T value = session.find(type).filter(dev.morphia.query.filters.Filters.eq("_id", id(id))).first();
      if (value == null) {
         throw new IllegalStateException("refName migration: " + type.getSimpleName() + " " + id + " is gone; "
               + "recompute the plan");
      }
      return value;
   }

   /** Stored ids are ObjectIds, except on records written by hand. */
   private static Object id(String value) {
      return ObjectId.isValid(value) ? new ObjectId(value) : value;
   }

   private static List<EntityReference> nullSafe(List<EntityReference> values) {
      return values == null ? List.of() : values;
   }

   private static String memberKey(EntityReference profile) {
      return profile.getEntityId() != null ? profile.getEntityId().toHexString() : "ref:" + profile.getEntityRefName();
   }
}

package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.annotations.CanonicalRefName;
import com.e2eq.framework.exceptions.RefNameViolationException;
import com.e2eq.framework.exceptions.RefNameViolationException.Code;
import com.e2eq.framework.model.persistent.base.RefNameRule;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enforces {@link CanonicalRefName} on the repository write paths.
 *
 * <ul>
 *    <li>Create (no id, or an id with no stored record): refName must satisfy {@link RefNameRule}. A declared legacy
 *    name is admitted only for framework writes, never inside a {@link #clientSuppliedRefNames()} scope.</li>
 *    <li>Update: refName must equal the stored value. On {@link WriteMode#MERGE} a null refName leaves the stored
 *    value untouched and is allowed. A record stored without a refName may keep none or be named once under the
 *    rule.</li>
 * </ul>
 * Stored refNames are read by {@code _id} outside the caller's security filters, because the check guards data
 * integrity rather than visibility. {@code skipValidation} does not bypass it.
 */
public final class RefNameContract {
   public static final String LEGACY_CONFIG_PREFIX = "quantum.refname.legacy-allowed.";

   /** How the write treats fields the caller left null. */
   public enum WriteMode {
      /** Full replace: a null refName would be persisted (or defaulted), so it counts as a change. */
      REPLACE,
      /** Merge: null fields are not written, so a null refName leaves the stored value untouched. */
      MERGE
   }

   private static final ThreadLocal<Integer> CLIENT_SUPPLIED = new ThreadLocal<>();

   private static final ClassValue<Set<String>> LEGACY_NAMES = new ClassValue<>() {
      @Override
      protected Set<String> computeValue(Class<?> type) {
         Set<String> names = new LinkedHashSet<>(List.of(type.getAnnotation(CanonicalRefName.class).legacy()));
         ConfigProvider.getConfig()
            .getOptionalValues(LEGACY_CONFIG_PREFIX + type.getSimpleName(), String.class)
            .ifPresent(names::addAll);
         return Collections.unmodifiableSet(names);
      }
   };

   private RefNameContract() {
   }

   public static boolean applies(Class<?> type) {
      return type.isAnnotationPresent(CanonicalRefName.class);
   }

   /**
    * Marks writes whose refNames come from an external client (REST bodies, imports). Inside this scope declared
    * legacy names are refused with {@link Code#REFNAME_RESERVED}, so a client cannot claim a name that framework
    * code later looks up (for example {@code tenant-admin-users}).
    */
   public static ClientScope clientSuppliedRefNames() {
      Integer depth = CLIENT_SUPPLIED.get();
      CLIENT_SUPPLIED.set(depth == null ? 1 : depth + 1);
      return new ClientScope();
   }

   public static final class ClientScope implements AutoCloseable {
      private boolean closed;

      private ClientScope() {
      }

      @Override
      public void close() {
         if (closed) {
            return;
         }
         closed = true;
         Integer depth = CLIENT_SUPPLIED.get();
         if (depth == null || depth <= 1) {
            CLIENT_SUPPLIED.remove();
         } else {
            CLIENT_SUPPLIED.set(depth - 1);
         }
      }
   }

   public static void enforceOnWrite(Datastore datastore, UnversionedBaseModel value, WriteMode mode) {
      enforceOnWrites(datastore, List.of(value), mode);
   }

   /** Checks a batch with one stored-refName read per entity class. */
   public static void enforceOnWrites(Datastore datastore, List<? extends UnversionedBaseModel> values, WriteMode mode) {
      Map<Class<?>, List<UnversionedBaseModel>> byType = new LinkedHashMap<>();
      for (UnversionedBaseModel value : values) {
         if (applies(value.getClass())) {
            byType.computeIfAbsent(value.getClass(), k -> new ArrayList<>()).add(value);
         }
      }
      for (Map.Entry<Class<?>, List<UnversionedBaseModel>> entry : byType.entrySet()) {
         Map<ObjectId, String> stored = storedRefNames(datastore, entry.getKey(), entry.getValue());
         for (UnversionedBaseModel value : entry.getValue()) {
            check(value, stored, mode);
         }
      }
   }

   /** Rejects refName in a field-level update for annotated types. */
   public static void enforceOnFieldUpdate(Class<?> type, String fieldPath) {
      if (applies(type) && "refName".equals(fieldPath)) {
         throw new RefNameViolationException(Code.REFNAME_IMMUTABLE, type.getSimpleName(), null,
            null, null, "refName is immutable after creation and cannot be set by a field update");
      }
   }

   /**
    * Exact legacy names, cached per type: those declared on the annotation plus
    * {@code quantum.refname.legacy-allowed.<SimpleClassName>} from configuration.
    */
   public static Set<String> legacyNames(Class<?> type) {
      return LEGACY_NAMES.get(type);
   }

   private static void check(UnversionedBaseModel value, Map<ObjectId, String> stored, WriteMode mode) {
      String entityType = value.getClass().getSimpleName();
      String entityId = value.getId() == null ? null : value.getId().toHexString();
      String offered = value.getRefName();

      if (value.getId() != null && stored.containsKey(value.getId())) {
         String current = stored.get(value.getId());
         if (offered == null && mode == WriteMode.MERGE) {
            return;
         }
         if (current == null || current.isEmpty()) {
            // Pre-rule record without a refName: it may keep none, or be named once under the rule.
            if (offered == null || offered.isEmpty()) {
               return;
            }
            checkNewName(value, offered, entityType, entityId);
            return;
         }
         if (!current.equals(offered)) {
            throw new RefNameViolationException(Code.REFNAME_IMMUTABLE, entityType, entityId,
               offered, current, "refName is immutable after creation");
         }
         return;
      }
      checkNewName(value, offered, entityType, entityId);
   }

   private static void checkNewName(UnversionedBaseModel value, String refName, String entityType, String entityId) {
      var violation = RefNameRule.check(refName);
      if (violation.isEmpty()) {
         return;
      }
      if (refName != null && legacyNames(value.getClass()).contains(refName)) {
         if (CLIENT_SUPPLIED.get() != null) {
            throw new RefNameViolationException(Code.REFNAME_RESERVED, entityType, entityId, refName, null,
               "'" + refName + "' is a reserved legacy name that only framework code may create; " + RefNameRule.DESCRIPTION);
         }
         Log.warnf("REFNAME_LEGACY_ADMITTED: creating %s with declared legacy refName '%s' (rule violation %s)",
            entityType, refName, violation.get());
         return;
      }
      throw new RefNameViolationException(violation.get(), entityType, entityId, refName, null,
         RefNameRule.DESCRIPTION);
   }

   private static Map<ObjectId, String> storedRefNames(Datastore datastore, Class<?> type,
                                                       List<UnversionedBaseModel> values) {
      List<ObjectId> ids = new ArrayList<>();
      for (UnversionedBaseModel value : values) {
         if (value.getId() != null) {
            ids.add(value.getId());
         }
      }
      if (ids.isEmpty()) {
         return Map.of();
      }
      Map<ObjectId, String> stored = new HashMap<>();
      try (MorphiaCursor<?> cursor = datastore.find(type)
         .filter(Filters.in("_id", ids))
         .iterator(new FindOptions().projection().include("refName"))) {
         while (cursor.hasNext()) {
            UnversionedBaseModel found = (UnversionedBaseModel) cursor.next();
            stored.put(found.getId(), found.getRefName());
         }
      }
      return stored;
   }
}

package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.ApplicationAccessException;
import com.e2eq.framework.model.security.ApplicationAccessException.Code;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * For repositories whose every write must pass their own validation (application bindings and
 * installs): every persisted field is server-owned, so the field-pair and bulk updates of
 * {@link MorphiaRepo} refuse them, and merges and status-only updates are refused outright. Only
 * {@code save} (and the repositories' own upserts) write.
 */
final class ValidatedWritesOnly {

   private ValidatedWritesOnly() {
   }

   /** Every non-static, non-transient field of {@code type} and its superclasses. */
   static Set<String> persistedFields(Class<?> type) {
      Set<String> fields = new LinkedHashSet<>();
      for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
         for (Field field : current.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers) && !field.isSynthetic()) {
               fields.add(field.getName());
            }
         }
      }
      return Set.copyOf(fields);
   }

   /**
    * Creates the unique index {@code name} on {@code keys}, first dropping a plain index on the
    * same keys (written before the index was unique), which would otherwise conflict with it.
    */
   static void ensureUniqueIndex(com.mongodb.client.MongoCollection<org.bson.Document> collection, String name,
                                 org.bson.conversions.Bson keys, org.bson.Document partialFilter) {
      org.bson.Document keyDocument = keys.toBsonDocument().entrySet().stream()
         .collect(org.bson.Document::new, (doc, entry) -> doc.append(entry.getKey(), entry.getValue().asNumber().intValue()),
            org.bson.Document::putAll);
      for (org.bson.Document existing : collection.listIndexes()) {
         Object key = existing.get("key");
         boolean sameKeys = key instanceof org.bson.Document document && document.equals(keyDocument);
         if (sameKeys && !name.equals(existing.getString("name")) && !Boolean.TRUE.equals(existing.get("unique"))) {
            collection.dropIndex(existing.getString("name"));
         }
      }
      collection.createIndex(keys, new com.mongodb.client.model.IndexOptions()
         .unique(true).name(name).partialFilterExpression(partialFilter));
   }

   static ApplicationAccessException refused(Code code, String operation, Class<?> type) {
      return new ApplicationAccessException(code, operation + " is not supported for " + type.getSimpleName()
         + ": every write is validated; save the whole record (or upsert it by id)");
   }
}

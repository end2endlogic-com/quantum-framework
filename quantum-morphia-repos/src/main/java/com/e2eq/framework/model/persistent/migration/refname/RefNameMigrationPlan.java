package com.e2eq.framework.model.persistent.migration.refname;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * What {@link CanonicalRefNameMigrator} would do to one realm. Reading it changes nothing.
 *
 * @param realm   the realm (database) the plan was computed for
 * @param actions one entry per stored record whose refName breaks the canonical rule
 */
@RegisterForReflection
public record RefNameMigrationPlan(String realm, List<Action> actions) {

   public enum Kind {
      /** No canonical record exists yet: the record is renamed in place. */
      RENAME,
      /** An equivalent canonical record exists: this one is folded into it and removed. */
      MERGE,
      /** A canonical record exists but differs, or several records map to one name: left for an operator. */
      CONFLICT,
      /** No valid canonical name can be derived (too short or long, leading digit, generated-looking id). */
      UNRESOLVABLE
   }

   /**
    * @param entity     model simple name, e.g. {@code Policy}
    * @param collection Mongo collection
    * @param id         the record's {@code _id}
    * @param from       stored refName
    * @param to         canonical refName (null when unresolvable)
    * @param targetId   the canonical record's {@code _id} for {@link Kind#MERGE} and {@link Kind#CONFLICT}
    * @param kind       what apply does with it
    * @param reason     why, for anything other than a plain rename
    */
   @RegisterForReflection
   public record Action(String entity, String collection, String id, String from, String to, String targetId,
                        Kind kind, String reason) {
   }

   public long count(Kind kind) {
      return actions.stream().filter(action -> action.kind() == kind).count();
   }

   /** Operator-facing summary lines, one per action. */
   public List<String> describe() {
      return actions.stream().map(action -> String.format("%s %s %s '%s'%s%s", action.kind(), action.entity(),
            action.id(), action.from(), action.to() == null ? "" : " -> '" + action.to() + "'",
            action.reason() == null ? "" : " (" + action.reason() + ")")).toList();
   }
}

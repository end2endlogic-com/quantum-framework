package com.e2eq.framework.model.persistent.migration.refname;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;

/**
 * Moves what a module stores about a record onto its canonical twin when {@link CanonicalRefNameMigrator} merges the
 * record away (for example, materialized ontology edges keyed by the record's id). Called inside the apply
 * transaction, so a participant's writes commit or abort with the merge.
 */
public interface RefNameMergeParticipant {

   /** How many stored items reference {@code id} in {@code database}; reads only, used to describe the plan. */
   long references(MongoDatabase database, String id);

   /**
    * How many stored items reference a record of {@code entityType} by its refName {@code refName} rather than its id.
    * The migration does not rewrite such references, so a rename or merge of a record that has any is reported as a
    * conflict instead.
    */
   long referencesByName(MongoDatabase database, String entityType, String refName);

   /**
    * Re-points everything that references {@code id} to {@code targetId}, in {@code session}. Items that would then
    * duplicate one the target already has are removed instead, after a write to the target's item so a concurrent
    * change to it aborts the transaction. Throws {@link IllegalStateException} when an item cannot be moved without
    * breaking a uniqueness constraint.
    */
   void merge(ClientSession session, MongoDatabase database, String id, String targetId);
}

package com.e2eq.framework.model.persistent.migration.base;

import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.smallrye.mutiny.subscription.MultiEmitter;

import java.util.Set;

/**
 * Defines a unit of database migration to be executed as part of the
 * framework's change set mechanism.
 */
public interface ChangeSetBean {
   public String getId();
   public int getDbFromVersionInt();
   public int getDbToVersionInt();
   public String getDbFromVersion();
   public String getDbToVersion();
   public int getPriority();
   public String getAuthor();
   public String getName();
   public String getDescription();
   public String getScope();
   public boolean isOverrideDatabase();
   public String getOverrideDatabaseName();
   // empty set implies all databases
   public Set<String> getApplicableDatabases();
   public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception;
}

package com.e2eq.framework.model.persistent.migration.base;

import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;

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
   public void execute(MorphiaSession session, MongoClient mongoClient, String realm) throws Exception;
}

package com.e2eq.framework.model.persistent.migration.base;

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
   public void execute(MorphiaSession session, String realm) throws Exception;
}

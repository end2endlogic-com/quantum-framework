package com.e2eq.framework.model.persistent.migration.base;

public interface ChangeSetBean {
   public String getId();
   public Double getDbFromVersion();
   public Double getDbToVersion();
   public int getPriority();
   public String getAuthor();
   public String getName();
   public String getDescription();
   public String getScope();
   public void execute(String realm) throws Exception;
}

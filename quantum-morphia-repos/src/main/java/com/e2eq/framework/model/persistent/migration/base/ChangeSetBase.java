package com.e2eq.framework.model.persistent.migration.base;


import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.MultiEmitter;

import java.util.HashSet;
import java.util.Set;

public abstract class ChangeSetBase implements ChangeSetBean {
   protected void log(String message, MultiEmitter<? super String> emitter) {
      Log.info(message);
      emitter.emit(message);
   }

   @Override
   public boolean isOverrideDatabase() {
      return false;
   }

   @Override
   public String getOverrideDatabaseName() {
      return null;
   }

   @Override
   public Set<String> getApplicableDatabases () {
      return null;
   }
}

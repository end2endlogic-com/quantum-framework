package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.EqualsAndHashCode;

import java.util.Date;

@RegisterForReflection
@EqualsAndHashCode
@Entity
public class AuditInfo {
   protected Date lastUpdateTs;
   protected String lastUpdateIdentity;
   protected Date creationTs;
   protected String creationIdentity;

   public Date getLastUpdateTs () {
      return lastUpdateTs;
   }

   public void setLastUpdateTs (Date lastUpdateTs) {
      this.lastUpdateTs = lastUpdateTs;
   }

   public String getLastUpdateIdentity () {
      return lastUpdateIdentity;
   }

   public void setLastUpdateIdentity (String lastUpdateIdentity) {
      this.lastUpdateIdentity = lastUpdateIdentity;
   }

   public Date getCreationTs () {
      return creationTs;
   }

   public void setCreationTs (Date creationTs) {
      this.creationTs = creationTs;
   }

   public String getCreationIdentity () {
      return creationIdentity;
   }

   public void setCreationIdentity (String creationIdentity) {
      this.creationIdentity = creationIdentity;
   }
}

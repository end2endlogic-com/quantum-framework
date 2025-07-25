package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Date;

@RegisterForReflection
@EqualsAndHashCode
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditInfo {
   protected Date creationTs;
   protected String creationIdentity;
   protected Date lastUpdateTs;
   protected String lastUpdateIdentity;
   protected String impersonatorUsername;
   protected String impersonatorUserId;
   protected String actingOnBehalfOfUsername;
   protected String actingOnBehalfOfUserId;

   public AuditInfo(Date createTs, String createIdentity, Date lastUpdateTs, String lastUpdateIdentity) {
      this.creationTs = createTs;
      this.creationIdentity = createIdentity;
      this.lastUpdateTs = lastUpdateTs;
      this.lastUpdateIdentity = lastUpdateIdentity;
   }
}

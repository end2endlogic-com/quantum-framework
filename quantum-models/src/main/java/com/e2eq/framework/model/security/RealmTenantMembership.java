package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RealmTenantMembership extends BaseModel {
   protected String realmRefName;
   protected String realmDisplayName;
   protected String organizationRefName;
   protected String accountId;
   protected String tenantId;
   protected String realmEmailDomain;
   protected String defaultAdminUserId;
   protected String realmEditionRefName;
   protected String provisioningMode;
   protected String participationStatus;
   protected String setupStatus;
   protected Integer setupCompletionPercent;

   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "REALM_MEMBERSHIP";
   }
}

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

   /**
    * Membership role of this org/account within the realm (B4 identity model;
    * realm-membership ADR): exactly one OWNER per realm (lifecycle authority:
    * provisioned it, can delete it, owns billing); any number of PARTICIPANTs
    * (collaborating orgs whose visibility is scoped by DataDomain +
    * relationship/field policies — e.g. carrier/supplier orgs in a
    * fulfillment realm). Defaults to owner for pre-existing records.
    */
   @lombok.Builder.Default
   protected String membershipRole = MEMBERSHIP_ROLE_OWNER;

   public static final String MEMBERSHIP_ROLE_OWNER = "owner";
   public static final String MEMBERSHIP_ROLE_PARTICIPANT = "participant";
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

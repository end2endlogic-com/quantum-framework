package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Locale;
import java.util.Objects;

@RegisterForReflection
@ToString
@EqualsAndHashCode
public final class SecurityURIBody {
  protected @NotNull String realm;
  protected @NotNull String orgRefName;
  protected @NotNull String accountNumber;
  protected @NotNull String tenantId;
  protected @NotNull String ownerId;
  protected @NotNull String dataSegment;
  protected String resourceId;

    /**
     * The body determines the "scope" that the rule applies to from a rule point of view.
     * So if a given rule applies based upon it matching the header, the body then provides the means to
     * define a scope, and can be used to build a filter or do other checks pre or post the action being taken.
     */
   public SecurityURIBody() {
      String any = "*";
      realm = any;
      orgRefName = any;
      accountNumber = any;
      tenantId = any;
      ownerId = any;
      resourceId=any;
      dataSegment = any;
   }

   public SecurityURIBody (String realm,  String orgRefName,  String accountNumber,
                           String tenantId, String dataSegment,
                           String ownerId,
                           String resourceId) {
      Objects.requireNonNull(realm, "realm cannot be null");
      Objects.requireNonNull(orgRefName, "orgRefName cannot be null");
      Objects.requireNonNull(accountNumber, "accountNumber cannot be null");
      Objects.requireNonNull(tenantId, "tenantId cannot be null");
      Objects.requireNonNull(ownerId, "ownerId cannot be null");

      this.realm = realm.toLowerCase();
      this.orgRefName = orgRefName.toLowerCase();
      this.accountNumber = accountNumber.toLowerCase();
      this.tenantId = tenantId.toLowerCase();
      if (dataSegment == null) {
         this.dataSegment = "*";
      }
      else {
         this.dataSegment = dataSegment.toLowerCase();
      }
      this.ownerId = ownerId.toLowerCase();
      this.resourceId = resourceId != null ? resourceId.toLowerCase() : null;
   }

   public static class Builder {
      String realm;
      String orgRefName;
      String accountNumber;
      String tenantId;
      String dataSegment;
      String ownerId;
      String resourceId;

      public SecurityURIBody.Builder withRealm ( String realm) {
         this.realm = realm.toLowerCase();
         return this;
      }

      public SecurityURIBody.Builder withOrgRefName (String orgRefName)
      {
         this.orgRefName =  orgRefName.toLowerCase();
         return this;
      }

      public SecurityURIBody.Builder withAccountNumber ( String accountNumber) {
         this.accountNumber = accountNumber.toLowerCase();
         return this;
      }

      public SecurityURIBody.Builder withTenantId (String tenantId) {
         this.tenantId = tenantId.toLowerCase();
         return this;
      }

      public SecurityURIBody.Builder withDataSegment (String seg) {
         this.dataSegment = seg != null ? seg.toLowerCase() : null;
         return this;
      }

      public SecurityURIBody.Builder withResourceId (String id) {
         this.resourceId = id != null ? id.toLowerCase() : null;
         return this;
      }

      public SecurityURIBody.Builder withOwnerId (String id) {
         this.ownerId = id != null ? id.toLowerCase() : null;
         return this;
      }

      public SecurityURIBody build () {
         return new SecurityURIBody(realm, orgRefName,  accountNumber, tenantId, dataSegment, ownerId, resourceId);
      }

   }

   public String getRealm () {
      return realm;
   }
   public void setRealm (String realm) {
      this.realm = realm.toLowerCase();
   }

   public String getOrgRefName () {return orgRefName;}
   public void setOrgRefName (String orgRefName) {this.orgRefName = orgRefName.toLowerCase();}

   public String getAccountNumber () {
      return accountNumber;
   }
   public void setAccountNumber (String accountNumber) {
      this.accountNumber = accountNumber.toLowerCase();
   }

   public String getTenantId () {
      return tenantId;
   }
   public void setTenantId (String tenantId) {
      this.tenantId = tenantId.toLowerCase();
   }

   public String getDataSegment () {
      return dataSegment;
   }
   public void setDataSegment (String dataSegment) {
      this.dataSegment = dataSegment.toLowerCase();
   }

   public String getOwnerId () {
      return ownerId;
   }
   public void setOwnerId (String ownerId) {
      this.ownerId = ownerId.toLowerCase();
   }

   public String getResourceId () {
      return resourceId;
   }
   public void setResourceId (String resourceId) {
      this.resourceId = resourceId.toLowerCase();
   }

   public SecurityURIBody clone () {
      return new SecurityURIBody.Builder()
         .withRealm(realm)
         .withOrgRefName(orgRefName)
         .withAccountNumber(accountNumber)
         .withTenantId(tenantId)
         .withOwnerId(ownerId)
         .withDataSegment(dataSegment)
         .withResourceId(resourceId)
         .build();
   }


   public String getURIString() {
      String rc = realm + ":" + orgRefName + ":" + accountNumber + ":" + tenantId + ":" + dataSegment + ":" + ownerId;

      if (resourceId != null) {
         rc = rc + ":" + resourceId;
      } else {
         rc = rc + ":*";
      }

      return rc;
   }
}

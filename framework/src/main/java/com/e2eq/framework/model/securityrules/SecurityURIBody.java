package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.validation.constraints.NotNull;

@RegisterForReflection
public final class SecurityURIBody {
  @NotNull String realm;
  @NotNull String orgRefName;
  @NotNull String accountNumber;
  @NotNull String tenantId;
  @NotNull String ownerId;
  @NotNull String dataSegment;
  String resourceId;

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

   public SecurityURIBody (@NotNull String realm, @NotNull String orgRefName, @NotNull String accountNumber,
                           @NotNull String tenantId, String dataSegment,
                           @NotNull String ownerId,
                           String resourceId) {
      this.realm = realm;
      this.orgRefName = orgRefName;
      this.accountNumber = accountNumber;
      this.tenantId = tenantId;
      if (dataSegment == null) {
         this.dataSegment = "*";
      }
      else {
         this.dataSegment = dataSegment;
      }
      this.ownerId = ownerId;
      this.resourceId = resourceId;
   }

   public static class Builder {
      String realm;
      String orgRefName;
      String accountNumber;
      String tenantId;
      String dataSegment;
      String ownerId;
      String resourceId;

      public SecurityURIBody.Builder withRealm (@NotNull String realm) {
         this.realm = realm;
         return this;
      }

      public SecurityURIBody.Builder withOrgRefName (@NotNull String orgRefName) {
         this.orgRefName = orgRefName;
         return this;
      }

      public SecurityURIBody.Builder withAccountNumber (@NotNull String accountNumber) {
         this.accountNumber = accountNumber;
         return this;
      }

      public SecurityURIBody.Builder withTenantId (@NotNull String tenantId) {
         this.tenantId = tenantId;
         return this;
      }

      public SecurityURIBody.Builder withDataSegment (String seg) {
         this.dataSegment = seg;
         return this;
      }

      public SecurityURIBody.Builder withResourceId (String id) {
         this.resourceId = resourceId;
         return this;
      }

      public SecurityURIBody.Builder withOwnerId (String id) {
         this.ownerId = id;
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
      this.realm = realm;
   }

   public String getOrgRefName () {return orgRefName;}
   public void setOrgRefName (String orgRefName) {this.orgRefName = orgRefName;}

   public String getAccountNumber () {
      return accountNumber;
   }
   public void setAccountNumber (String accountNumber) {
      this.accountNumber = accountNumber;
   }

   public String getTenantId () {
      return tenantId;
   }
   public void setTenantId (String tenantId) {
      this.tenantId = tenantId;
   }

   public String getDataSegment () {
      return dataSegment;
   }
   public void setDataSegment (String dataSegment) {
      this.dataSegment = dataSegment;
   }

   public String getOwnerId () {
      return ownerId;
   }
   public void setOwnerId (String ownerId) {
      this.ownerId = ownerId;
   }

   public String getResourceId () {
      return resourceId;
   }
   public void setResourceId (String resourceId) {
      this.resourceId = resourceId;
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


   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof SecurityURIBody)) return false;

      SecurityURIBody that = (SecurityURIBody) o;

      if (realm != null ? !realm.equals(that.realm) : that.realm != null) return false;
      if (orgRefName != null ? !orgRefName.equals(that.orgRefName) : that.orgRefName != null) return false;
      if (accountNumber != null ? !accountNumber.equals(that.accountNumber) : that.accountNumber != null) return false;
      if (tenantId != null ? !tenantId.equals(that.tenantId) : that.tenantId != null) return false;
      if (ownerId != null ? !ownerId.equals(that.ownerId) : that.ownerId != null) return false;
      if (dataSegment != null ? !dataSegment.equals(that.dataSegment) : that.dataSegment != null) return false;
      return resourceId != null ? resourceId.equals(that.resourceId) : that.resourceId == null;
   }

   @Override
   public int hashCode () {
      int result = realm != null ? realm.hashCode() : 0;
      result = 31 * result + (orgRefName != null ? orgRefName.hashCode() : 0);
      result = 31 * result + (accountNumber != null ? accountNumber.hashCode() : 0);
      result = 31 * result + (tenantId != null ? tenantId.hashCode() : 0);
      result = 31 * result + (ownerId != null ? ownerId.hashCode() : 0);
      result = 31 * result + (dataSegment != null ? dataSegment.hashCode() : 0);
      result = 31 * result + (resourceId != null ? resourceId.hashCode() : 0);
      return result;
   }

   public String toString () {
      String rc = realm + ":" + orgRefName + ":" + accountNumber + ":" + tenantId + ":" + dataSegment + ":" + ownerId;

      if (resourceId != null) {
         rc = rc + ":" + resourceId;
      } else {
         rc = rc + ":*";
      }

      return rc;
   }
}

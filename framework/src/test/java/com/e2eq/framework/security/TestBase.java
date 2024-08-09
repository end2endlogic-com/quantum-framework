package com.e2eq.framework.security;

public class TestBase {
   String ownerId;
   String tenantId;
   String accountId;
   int dataSegment;
   String type;

   public String getOwnerId () {
      return ownerId;
   }

   public void setOwnerId (String ownerId) {
      this.ownerId = ownerId;
   }

   public String getTenantId () {
      return tenantId;
   }

   public void setTenantId (String tenantId) {
      this.tenantId = tenantId;
   }

   public String getAccountId () {
      return accountId;
   }

   public void setAccountId (String accountId) {
      this.accountId = accountId;
   }

   public int getDataSegment () {
      return dataSegment;
   }

   public void setDataSegment (int dataSegment) {
      this.dataSegment = dataSegment;
   }

   public String getType () {
      return type;
   }

   public void setType (String type) {
      this.type = type;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof TestBase)) return false;

      TestBase testBase = (TestBase) o;

      if (dataSegment != testBase.dataSegment) return false;
      if (ownerId != null ? !ownerId.equals(testBase.ownerId) : testBase.ownerId != null) return false;
      if (tenantId != null ? !tenantId.equals(testBase.tenantId) : testBase.tenantId != null) return false;
      if (accountId != null ? !accountId.equals(testBase.accountId) : testBase.accountId != null) return false;
      return type != null ? type.equals(testBase.type) : testBase.type == null;
   }

   @Override
   public int hashCode () {
      int result = ownerId != null ? ownerId.hashCode() : 0;
      result = 31 * result + (tenantId != null ? tenantId.hashCode() : 0);
      result = 31 * result + (accountId != null ? accountId.hashCode() : 0);
      result = 31 * result + dataSegment;
      result = 31 * result + (type != null ? type.hashCode() : 0);
      return result;
   }
}

package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

public class AuthRequest  {
   @JsonProperty(required = true)
   protected @NotNull String userId;
   protected String email;
   @JsonProperty(required = true)
   protected @NotNull String password;
   protected String tenantId;
   protected String accountId;
   protected String realm;
   protected boolean rememberme;

   public String getUserId () {
      return userId;
   }

   public void setUserId (String userId) {
      this.userId = userId;
   }

   public String getEmail () {
      return email;
   }

   public void setEmail (String email) {
      this.email = email;
   }

   public String getPassword () {
      return password;
   }

   public void setPassword (String password) {
      this.password = password;
   }

   public String getTenantId () {return tenantId;}

   public void setTenantId (String tenantId) {this.tenantId = tenantId;}

   public boolean isRememberme () {
      return rememberme;
   }

   public void setRememberme (boolean rememberme) {
      this.rememberme = rememberme;
   }

   public String getAccountId () {
      return accountId;
   }

   public void setAccountId (String accountId) {
      this.accountId = accountId;
   }

   public String getRealm () {
      return realm;
   }

   public void setRealm (String realm) {
      this.realm = realm;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof AuthRequest)) return false;

      AuthRequest that = (AuthRequest) o;

      if (rememberme != that.rememberme) return false;
      if (userId != null ? !userId.equals(that.userId) : that.userId != null) return false;
      if (email != null ? !email.equals(that.email) : that.email != null) return false;
      if (password != null ? !password.equals(that.password) : that.password != null) return false;
      if (tenantId != null ? !tenantId.equals(that.tenantId) : that.tenantId != null) return false;
      if (accountId != null ? !accountId.equals(that.accountId) : that.accountId != null) return false;
      return realm != null ? realm.equals(that.realm) : that.realm == null;
   }

   @Override
   public int hashCode () {
      int result = userId != null ? userId.hashCode() : 0;
      result = 31 * result + (email != null ? email.hashCode() : 0);
      result = 31 * result + (password != null ? password.hashCode() : 0);
      result = 31 * result + (tenantId != null ? tenantId.hashCode() : 0);
      result = 31 * result + (accountId != null ? accountId.hashCode() : 0);
      result = 31 * result + (realm != null ? realm.hashCode() : 0);
      result = 31 * result + (rememberme ? 1 : 0);
      return result;
   }

}

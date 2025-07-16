package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.graalvm.polyglot.HostAccess;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;

/**
 * PrincipalContext holds the contextual information about the authenticated principal.
 * This includes the realm that this principal was authenticated against,
 * the Data Domain that the principal is associated with.  The userId of the principal,
 * the roles the principal has been assigned.
 * The scope of the authentication that is taking place
 */
@RegisterForReflection
public final class PrincipalContext {
   @NotNull String defaultRealm;           // The realm that this Principal came from
   @NotNull
   DataDomain dataDomain;  // org, account, tenant, ds, owner
   @NotNull String userId;          // The userId, of this principal
   @NotNull String[] roles;         // The roles associated with this principal
   @NotNull String scope;
   String impersonatedByUsername;// The scope under which this context was built, be that for authentication purposes or refresh purposes
   String impersonatedByUserId; // The scope under which this context was built, be that for authentication purposes or refresh purposes
   String actingOnBehalfOfUsername;
   String actingOnBehalfOfUserId;

   PrincipalContext(@NotNull String defaultRealm,
                    @Valid @NotNull DataDomain dataDomain,
                    @NotNull String userId,
                    @NotNull String[] roles,
                    @NotNull String scope) {
      this.defaultRealm = defaultRealm;
      this.dataDomain = dataDomain;
      this.userId = userId;
      this.roles = roles;
      this.scope = scope;
      this.impersonatedByUsername = null;
      this.impersonatedByUserId = null;
      this.actingOnBehalfOfUserId = null;
      this.actingOnBehalfOfUsername = null;
   }

   PrincipalContext(@NotNull String defaultRealm,
                    @Valid @NotNull DataDomain dataDomain,
                    @NotNull String userId,
                    @NotNull String[] roles,
                    @NotNull String scope,
                    String impersonatedByUsername,
                    String impersonatedByUserId,
                    String actingOnBehalfOfUserId,
                    String actingOnBehalfOfUsername) {
      this.defaultRealm = defaultRealm;
      this.dataDomain = dataDomain;
      this.userId = userId;
      this.roles = roles;
      this.scope = scope;
      this.impersonatedByUsername = impersonatedByUsername;
      this.impersonatedByUserId = impersonatedByUserId;
      this.actingOnBehalfOfUsername = actingOnBehalfOfUsername;
      this.actingOnBehalfOfUserId = actingOnBehalfOfUserId;
   }

   public static class Builder {

      String defaultRealm = null;
      DataDomain dataDomain = null;
      String userId = null;
      String[] roles = null;
      String scope= null;
      String impersonatedByUsername;
      String impersonatedByUserId;
      String actingOnBehalfOfUserId;
      String actingOnBehalfOfUsername;

      public Builder withDefaultRealm(String realm) {
         this.defaultRealm = realm;
         return this;
      }
      public Builder withDataDomain(@Valid @NotNull DataDomain dataDomain) {
         this.dataDomain = dataDomain;
         return this;
      }
      public Builder withUserId(String userId) {
         this.userId = userId;
         return this;
      }
      public Builder withRoles(String[] roles) {
         this.roles = roles;
         return this;
      }
      public Builder withScope(String scope){
         this.scope = scope;
         return this;
      }

      public Builder withImpersonatedByUsername(String username) {
         this.impersonatedByUsername = username;
         return this;
      }

      public Builder withImpersonatedByUserId(String userId) {
         this.impersonatedByUserId = userId;
         return this;
      }

      public Builder withActingOnBehalfOfUserId(String userId) {
         this.actingOnBehalfOfUserId = userId;
         return this;
      }

      public Builder withActingOnBehalfOfUsername(String username) {
         this.actingOnBehalfOfUsername = username;
         return this;
      }

      public PrincipalContext build() {
         return
            new PrincipalContext(defaultRealm, dataDomain, userId, roles, scope,
               impersonatedByUsername, impersonatedByUserId, actingOnBehalfOfUsername, actingOnBehalfOfUserId);
      }

   }

   @HostAccess.Export
   public String getDefaultRealm () {
      return defaultRealm;
   }

   public void setDefaultRealm (String defaultRealm) {
      this.defaultRealm = defaultRealm;
   }

   @HostAccess.Export
   public DataDomain getDataDomain() {return dataDomain;}
   public void setDataDomain(DataDomain dataDomain) {this.dataDomain = dataDomain;}

   @HostAccess.Export
   public String getUserId () {
      return userId;
   }
   public void setUserId (String userId) {
      this.userId = userId;
   }

   @HostAccess.Export
   public String[] getRoles () {
      return roles;
   }

   public void setRoles (String[] roles) {
      this.roles = roles;
   }

   @HostAccess.Export
   public String getScope () {
      return scope;
   }

   public void setScope (String scope) {
      this.scope = scope;
   }

   @HostAccess.Export
   public String getImpersonatedByUsername() {
      return impersonatedByUsername;
   }

   public void setImpersonatedByUsername(String impersonatedByUsername) {
      this.impersonatedByUsername = impersonatedByUsername;
   }

   @HostAccess.Export
   public String getImpersonatedByUserId() {
      return impersonatedByUserId;
   }


   public void setImpersonatedByUserId(String impersonatedByUserId) {
      this.impersonatedByUserId = impersonatedByUserId;
   }

   @HostAccess.Export
   public String getActingOnBehalfOfUserId() {
      return actingOnBehalfOfUserId;
   }

   public void setActingOnBehalfOfUserId(String userId) {
      this.actingOnBehalfOfUserId = userId;
   }

   @HostAccess.Export
   public String getActingOnBehalfOfUsername() {
      return actingOnBehalfOfUsername;
   }

   public void setActingOnBehalfOfUsername(String username) {
      this.actingOnBehalfOfUsername = username;
   }



   @HostAccess.Export
   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof PrincipalContext)) return false;

      PrincipalContext that = (PrincipalContext) o;

      if (defaultRealm != null ? !defaultRealm.equals(that.defaultRealm) : that.defaultRealm != null) return false;
      if (dataDomain != null ? !dataDomain.equals(that.dataDomain) : that.dataDomain != null) return false;
      if (userId != null ? !userId.equals(that.userId) : that.userId != null) return false;
      if (impersonatedByUsername != null ? !impersonatedByUsername.equals(that.impersonatedByUsername) : that.impersonatedByUsername != null) return false;
      if (impersonatedByUserId != null ? !impersonatedByUserId.equals(that.impersonatedByUserId) : that.impersonatedByUserId != null) return false;
      if (actingOnBehalfOfUsername != null ? !actingOnBehalfOfUsername.equals(that.actingOnBehalfOfUsername) : that.actingOnBehalfOfUsername != null) return false;
      if (actingOnBehalfOfUserId != null ? !actingOnBehalfOfUserId.equals(that.actingOnBehalfOfUserId) : that.actingOnBehalfOfUserId != null) return false;
      // Probably incorrect - comparing Object[] arrays with Arrays.equals
      if (!Arrays.equals(roles, that.roles)) return false;
      return scope != null ? scope.equals(that.scope) : that.scope == null;
   }

   @Override
   @HostAccess.Export
   public int hashCode () {
      int result = defaultRealm != null ? defaultRealm.hashCode() : 0;
      result = 31 * result + (dataDomain != null ? dataDomain.hashCode() : 0);
      result = 31 * result + (userId != null ? userId.hashCode() : 0);
      result = 31 * result + Arrays.hashCode(roles);
      result = 31 * result + (scope != null ? scope.hashCode() : 0);
      result = 31 * result + (impersonatedByUsername!= null? impersonatedByUsername.hashCode() : 0);
      result = 31 * result + (impersonatedByUserId!= null? impersonatedByUserId.hashCode() : 0);
      result = 31 * result + (actingOnBehalfOfUsername!= null? actingOnBehalfOfUsername.hashCode() : 0);
      result = 31 * result + (actingOnBehalfOfUserId!= null? actingOnBehalfOfUserId.hashCode() : 0);
      return result;
   }

   @Override
   public String toString () {
      return "PrincipalContext{" +
                "realm='" + defaultRealm + '\'' +
                ", dataDomain=" + dataDomain +
                ", userId='" + userId + '\'' +
                ", roles=" + Arrays.toString(roles) +
                ", scope='" + scope + '\'' +
                ", impersonatedByUsername='" + impersonatedByUsername + '\'' +
                ", impersonatedByUserId='" + impersonatedByUserId + '\'' +
                ", actingOnBehalfOfUserId='" + actingOnBehalfOfUserId + '\'' +
                ", actingOnBehalfOfUsername='" + actingOnBehalfOfUsername + '\'' +
                '}';
   }
}

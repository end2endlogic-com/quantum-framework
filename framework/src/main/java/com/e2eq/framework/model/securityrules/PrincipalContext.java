package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.Pattern;
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
   @NotNull String scope;           // The scope under which this context was built, be that for authentication purposes or refresh purposes

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
   }

   public static class Builder {

      String defaultRealm = null;
      DataDomain dataDomain = null;
      String userId = null;
      String[] roles = null;
      String scope= null;

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

      public PrincipalContext build() {
         return
            new PrincipalContext(defaultRealm, dataDomain, userId, roles, scope);
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
   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof PrincipalContext)) return false;

      PrincipalContext that = (PrincipalContext) o;

      if (defaultRealm != null ? !defaultRealm.equals(that.defaultRealm) : that.defaultRealm != null) return false;
      if (dataDomain != null ? !dataDomain.equals(that.dataDomain) : that.dataDomain != null) return false;
      if (userId != null ? !userId.equals(that.userId) : that.userId != null) return false;
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
                '}';
   }
}

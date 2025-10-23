package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainPolicy;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.graalvm.polyglot.HostAccess;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.Map;

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
   String impersonatedBySubject;// The scope under which this context was built, be that for authentication purposes or refresh purposes
   String impersonatedByUserId; // The scope under which this context was built, be that for authentication purposes or refresh purposes
   String actingOnBehalfOfSubject;
   String actingOnBehalfOfUserId;
   Map<String, String> area2RealmOverrides;
   // Optional: data domain policy attached to the principal's credential
   DataDomainPolicy dataDomainPolicy;

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
      this.impersonatedBySubject = null;
      this.impersonatedByUserId = null;
      this.actingOnBehalfOfUserId = null;
      this.actingOnBehalfOfSubject = null;
   }

   PrincipalContext(@NotNull String defaultRealm,
                    @Valid @NotNull DataDomain dataDomain,
                    @NotNull String userId,
                    @NotNull String[] roles,
                    @NotNull String scope,
                    String impersonatedBySubject,
                    String impersonatedByUserId,
                    String actingOnBehalfOfUserId,
                    String actingOnBehalfOfSubject) {
      this.defaultRealm = defaultRealm;
      this.dataDomain = dataDomain;
      this.userId = userId;
      this.roles = roles;
      this.scope = scope;
      this.impersonatedBySubject = impersonatedBySubject;
      this.impersonatedByUserId = impersonatedByUserId;
      this.actingOnBehalfOfSubject = actingOnBehalfOfSubject;
      this.actingOnBehalfOfUserId = actingOnBehalfOfUserId;
   }



   public static class Builder {

      String defaultRealm = null;
      DataDomain dataDomain = null;
      String userId = null;
      String[] roles = null;
      String scope= null;
      String impersonatedBySubject;
      String impersonatedByUserId;
      String actingOnBehalfOfUserId;
      String actingOnBehalfOfSubject;
      Map<String, String> area2RealmOverrides;
      DataDomainPolicy dataDomainPolicy;

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

      public Builder withImpersonatedBySubject(String subject) {
         this.impersonatedBySubject = subject;
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

      public Builder withActingOnBehalfOfSubject(String subject) {
         this.actingOnBehalfOfSubject = subject;
         return this;
      }

      public Builder withArea2RealmOverrides(Map<String, String> overrides) {
         this.area2RealmOverrides = overrides;
         return this;
      }

      public Builder withDataDomainPolicy(DataDomainPolicy policy) {
         this.dataDomainPolicy = policy;
         return this;
      }

      public PrincipalContext build() {
         PrincipalContext pc =
            new PrincipalContext(defaultRealm, dataDomain, userId, roles, scope,
               impersonatedBySubject, impersonatedByUserId, actingOnBehalfOfSubject, actingOnBehalfOfUserId);
         pc.area2RealmOverrides = this.area2RealmOverrides;
         pc.dataDomainPolicy = this.dataDomainPolicy;
         return pc;
      }

   }

   @HostAccess.Export
   public String getDefaultRealm () {
      return defaultRealm;
   }

   @HostAccess.Export
   public Map<String, String> getArea2RealmOverrides () {
      return area2RealmOverrides;
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
   public String getImpersonatedBySubject() {
      return impersonatedBySubject;
   }

   public void setImpersonatedBySubject(String impersonatedBySubject) {
      this.impersonatedBySubject = impersonatedBySubject;
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
   public String getActingOnBehalfOfSubject() {
      return actingOnBehalfOfSubject;
   }

   public void setActingOnBehalfOfSubject(String subject) {
      this.actingOnBehalfOfSubject = subject;
   }

   public DataDomainPolicy getDataDomainPolicy() {
      return dataDomainPolicy;
   }

   public void setDataDomainPolicy(DataDomainPolicy dataDomainPolicy) {
      this.dataDomainPolicy = dataDomainPolicy;
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
      if (impersonatedBySubject != null ? !impersonatedBySubject.equals(that.impersonatedBySubject) : that.impersonatedBySubject != null) return false;
      if (impersonatedByUserId != null ? !impersonatedByUserId.equals(that.impersonatedByUserId) : that.impersonatedByUserId != null) return false;
      if (actingOnBehalfOfSubject != null ? !actingOnBehalfOfSubject.equals(that.actingOnBehalfOfSubject) : that.actingOnBehalfOfSubject != null) return false;
      if (actingOnBehalfOfUserId != null ? !actingOnBehalfOfUserId.equals(that.actingOnBehalfOfUserId) : that.actingOnBehalfOfUserId != null) return false;
      // Probably incorrect - comparing Object[] arrays with Arrays.equals
      if (!Arrays.equals(roles, that.roles)) return false;
      if (scope != null ? !scope.equals(that.scope) : that.scope != null) return false;
      if (dataDomainPolicy != null ? !dataDomainPolicy.equals(that.dataDomainPolicy) : that.dataDomainPolicy != null) return false;
      return true;
   }

   @Override
   @HostAccess.Export
   public int hashCode () {
      int result = defaultRealm != null ? defaultRealm.hashCode() : 0;
      result = 31 * result + (dataDomain != null ? dataDomain.hashCode() : 0);
      result = 31 * result + (userId != null ? userId.hashCode() : 0);
      result = 31 * result + Arrays.hashCode(roles);
      result = 31 * result + (scope != null ? scope.hashCode() : 0);
      result = 31 * result + (impersonatedBySubject!= null? impersonatedBySubject.hashCode() : 0);
      result = 31 * result + (impersonatedByUserId!= null? impersonatedByUserId.hashCode() : 0);
      result = 31 * result + (actingOnBehalfOfSubject!= null? actingOnBehalfOfSubject.hashCode() : 0);
      result = 31 * result + (actingOnBehalfOfUserId!= null? actingOnBehalfOfUserId.hashCode() : 0);
      result = 31 * result + (dataDomainPolicy != null ? dataDomainPolicy.hashCode() : 0);
      return result;
   }

   @Override
   public String toString () {
      return "PrincipalContext{" +
               "defaultRealm='" + defaultRealm + '\'' +
               ", dataDomain=" + dataDomain +
               ", userId='" + userId + '\'' +
               ", roles=" + Arrays.toString(roles) +
               ", scope='" + scope + '\'' +
               ", impersonatedBySubject='" + impersonatedBySubject + '\'' +
               ", impersonatedByUserId='" + impersonatedByUserId + '\'' +
               ", actingOnBehalfOfUserId='" + actingOnBehalfOfUserId + '\'' +
               ", actingOnBehalfOfSubject='" + actingOnBehalfOfSubject + '\'' +
               '}';
   }
}

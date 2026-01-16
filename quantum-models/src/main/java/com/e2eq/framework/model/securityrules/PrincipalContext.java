package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DomainContext;
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
   @NotNull( message = "defaultRealm must be non null, needs to be the realm that this principal will use by default")
   String defaultRealm;           // The realm that this Principal came from
   @NotNull( message = "the data domain must be non null, needs to be the org, account, tenant, ds, or owner for this principal")
   DataDomain dataDomain;  // org, account, tenant, ds, owner

   @NotNull(message = "userId must be non null, needs to be the userId of the principal")
   String userId;          // The userId, of this principal
   @NotNull(message = "roles must be non null, needs to be the roles of the principal, pass an empty array if there are no roles")
   @HostAccess.Export
   @NotNull String[] roles;
   // The roles associated with this principal
   @NotNull (message = "scope must be non null, needs to be the scope of the principal")
   String scope;
   String impersonatedBySubject;// The scope under which this context was built, be that for authentication purposes or refresh purposes
   String impersonatedByUserId; // The scope under which this context was built, be that for authentication purposes or refresh purposes
   String actingOnBehalfOfSubject;
   String actingOnBehalfOfUserId;
   Map<String, String> area2RealmOverrides;
   // Optional: data domain policy attached to the principal's credential
   DataDomainPolicy dataDomainPolicy;
   
   // Realm override tracking - when X-Realm header is used
   boolean realmOverrideActive;       // True if X-Realm override is in effect
   DataDomain originalDataDomain;     // The caller's original DataDomain before realm override (for audit)

   // DomainContext provides complete realm context including tenantId, orgRefName, accountId, dataSegment
   // When set, getDefaultRealm() delegates to domainContext.getDefaultRealm()
   DomainContext domainContext;

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
      boolean realmOverrideActive;
      DataDomain originalDataDomain;
      DomainContext domainContext;

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

      /**
       * Indicates that an X-Realm header override is in effect, meaning the DataDomain
       * has been switched to the target realm's default DataDomain.
       * @param active true if realm override is active
       * @return this builder
       */
      public Builder withRealmOverrideActive(boolean active) {
         this.realmOverrideActive = active;
         return this;
      }

      /**
       * The caller's original DataDomain before realm override was applied.
       * Useful for audit purposes when X-Realm is used.
       * @param original the original DataDomain
       * @return this builder
       */
      public Builder withOriginalDataDomain(DataDomain original) {
         this.originalDataDomain = original;
         return this;
      }

      /**
       * Sets the DomainContext for this principal. When set, getDefaultRealm()
       * will delegate to domainContext.getDefaultRealm() rather than using the
       * standalone defaultRealm field. This provides a single source of truth
       * for realm resolution.
       * @param domainContext the domain context
       * @return this builder
       */
      public Builder withDomainContext(DomainContext domainContext) {
         this.domainContext = domainContext;
         return this;
      }

      public PrincipalContext build() {
         PrincipalContext pc =
            new PrincipalContext(defaultRealm, dataDomain, userId, roles, scope,
               impersonatedBySubject, impersonatedByUserId, actingOnBehalfOfUserId, actingOnBehalfOfSubject);
         pc.area2RealmOverrides = this.area2RealmOverrides;
         pc.dataDomainPolicy = this.dataDomainPolicy;
         pc.realmOverrideActive = this.realmOverrideActive;
         pc.originalDataDomain = this.originalDataDomain;
         pc.domainContext = this.domainContext;
         return pc;
      }

   }

   /**
    * Returns the default realm for this principal. If a DomainContext is set,
    * this delegates to domainContext.getDefaultRealm(). Otherwise, returns
    * the standalone defaultRealm field.
    * @return the default realm
    */
   @HostAccess.Export
   public String getDefaultRealm () {
      // Delegation: if domainContext is set, use its defaultRealm
      if (domainContext != null && domainContext.getDefaultRealm() != null) {
         return domainContext.getDefaultRealm();
      }
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

   /**
    * Returns true if an X-Realm header override is in effect, meaning the DataDomain
    * has been switched to the target realm's default DataDomain rather than the caller's.
    * @return true if realm override is active
    */
   @HostAccess.Export
   public boolean isRealmOverrideActive() {
      return realmOverrideActive;
   }

   public void setRealmOverrideActive(boolean realmOverrideActive) {
      this.realmOverrideActive = realmOverrideActive;
   }

   /**
    * Returns the caller's original DataDomain before realm override was applied.
    * This is null if no realm override is active. Useful for audit purposes.
    * @return the original DataDomain or null
    */
   @HostAccess.Export
   public DataDomain getOriginalDataDomain() {
      return originalDataDomain;
   }

   public void setOriginalDataDomain(DataDomain originalDataDomain) {
      this.originalDataDomain = originalDataDomain;
   }

   /**
    * Returns the DomainContext if set. This provides complete realm context
    * including tenantId, orgRefName, accountId, and dataSegment.
    * @return the DomainContext or null
    */
   @HostAccess.Export
   public DomainContext getDomainContext() {
      return domainContext;
   }

   public void setDomainContext(DomainContext domainContext) {
      this.domainContext = domainContext;
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
      if (realmOverrideActive != that.realmOverrideActive) return false;
      if (originalDataDomain != null ? !originalDataDomain.equals(that.originalDataDomain) : that.originalDataDomain != null) return false;
      if (domainContext != null ? !domainContext.equals(that.domainContext) : that.domainContext != null) return false;
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
      result = 31 * result + (realmOverrideActive ? 1 : 0);
      result = 31 * result + (originalDataDomain != null ? originalDataDomain.hashCode() : 0);
      result = 31 * result + (domainContext != null ? domainContext.hashCode() : 0);
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
               ", realmOverrideActive=" + realmOverrideActive +
               ", originalDataDomain=" + originalDataDomain +
               ", domainContext=" + domainContext +
               '}';
   }
}

package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.graalvm.polyglot.HostAccess;

import jakarta.validation.constraints.NotNull;
import java.util.Optional;


@RegisterForReflection
public class ResourceContext {
  @NotNull String realm;              // The realm this resource is in
  @NotNull  String area;               // the area the resource resides in
  @NotNull String functionalDomain;   // the functional domain with in the area
  @NotNull String action;             // the action we are trying to take
  @NotNull String resourceId;         // the id (objectId) if known

   public static ResourceContext DEFAULT_ANONYMOUS_CONTEXT = new ResourceContext("NONE", "NONE", "NONE", "NONE", null);

   ResourceContext(@NotNull(message="realmId can not be null") String realm,
                   @NotNull(message="area can not be null") String area,
                   @NotNull(message="functional domain can not be null") String functionalDomain,
                   @NotNull(message="action can not be null") String action,
                   String resourceId) {


      this.realm = realm;
      this.area = area;
      this.functionalDomain = functionalDomain;
      this.action = action;
      this.resourceId = resourceId;
   }

   public static class Builder {
      String any = "*";
      String realm = any;

      String area = any;
      String functionalDomain = any;
      String action = any;
      String resourceId = any;

      public Builder withRealm(String realm) {
         this.realm = realm;
         return this;
      }

      public Builder withArea(String area) {
         this.area = area;
         return this;
      }

      public Builder withFunctionalDomain(String fd) {
         this.functionalDomain = fd;
         return this;
      }

      public Builder withAction(String action) {
         this.action = action;
         return this;
      }

      public Builder withResourceId(String id) {
         this.resourceId = resourceId;
         return this;
      }

      public ResourceContext build() {
         return new ResourceContext( realm, area,
            functionalDomain, action, resourceId);
      }
   };

   @HostAccess.Export
   public String getArea () {
      return area;
   }
   public void setArea (String area) {
      this.area = area;
   }

   @HostAccess.Export
   public String getFunctionalDomain () {
      return functionalDomain;
   }
   public void setFunctionalDomain (String functionalDomain) {
      this.functionalDomain = functionalDomain;
   }

   @HostAccess.Export
   public String getAction () {
      return action;
   }
   public void setAction (String action) {
      this.action = action;
   }

   @HostAccess.Export
   public Optional<String> getResourceId () {
      return Optional.ofNullable(resourceId);
   }
   public void setResourceId (String resourceId) {
      this.resourceId = resourceId;
   }

   @HostAccess.Export
   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof ResourceContext)) return false;

      ResourceContext that = (ResourceContext) o;

      if (realm != null ? !realm.equals(that.realm) : that.realm != null) return false;

      if (area != null ? !area.equals(that.area) : that.area != null) return false;
      if (functionalDomain != null ? !functionalDomain.equals(that.functionalDomain) : that.functionalDomain != null)
         return false;
      if (action != null ? !action.equals(that.action) : that.action != null) return false;
      return resourceId != null ? resourceId.equals(that.resourceId) : that.resourceId == null;
   }

   @HostAccess.Export
   @Override
   public int hashCode () {
      int result = realm != null ? realm.hashCode() : 0;
      result = 31 * result + (area != null ? area.hashCode() : 0);
      result = 31 * result + (functionalDomain != null ? functionalDomain.hashCode() : 0);
      result = 31 * result + (action != null ? action.hashCode() : 0);
      result = 31 * result + (resourceId != null ? resourceId.hashCode() : 0);
      return result;
   }

   @Override
   public String toString () {
      return "ResourceContext{" +
                "realm='" + realm + '\'' +
                ", area='" + area + '\'' +
                ", functionalDomain='" + functionalDomain + '\'' +
                ", action='" + action + '\'' +
                ", resourceId='" + resourceId + '\'' +
                '}';
   }
}
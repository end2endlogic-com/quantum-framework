package com.e2eq.framework.model.securityrules;

import com.e2eq.framework.model.persistent.base.DataDomain;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.graalvm.polyglot.HostAccess;

import jakarta.validation.constraints.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ResourceContext holds the contextual information about the resource being accessed.
 * The resource is part of a certain realm, area, and functional domain.  An action is being performed on the resource
 * by a resourceId, and the resource is owned by an ownerId.
 *
 * This information is then used by rules to determine if the action should take place or not
 */

@RegisterForReflection
public class ResourceContext {
  protected @NotNull String realm;              // The realm this resource is in
  protected @NotNull  String area;               // the area the resource resides in
  protected @NotNull String functionalDomain;   // the functional domain with in the area
  protected @NotNull String action;             // the action we are trying to take
  protected String resourceId; // the id (objectId) if known
  protected String ownerId; // the id (ownerId) if known
  protected DataDomain dataDomain; // effective data-domain attributes for this resource access
  protected Map<String, Object> attributes; // optional extension attributes for policy/debug consumers

   public static ResourceContext DEFAULT_ANONYMOUS_CONTEXT = new ResourceContext("NONE", "NONE", "NONE", "NONE", null, null);

   ResourceContext(@NotNull(message="realmId can not be null") String realm,
                   @NotNull(message="area can not be null") String area,
                   @NotNull(message="functional domain can not be null") String functionalDomain,
                   @NotNull(message="action can not be null") String action,
                   String resourceId,
                   String ownerId) {
      this(realm, area, functionalDomain, action, resourceId, ownerId, null, Collections.emptyMap());
   }

   ResourceContext(@NotNull(message="realmId can not be null") String realm,
                   @NotNull(message="area can not be null") String area,
                   @NotNull(message="functional domain can not be null") String functionalDomain,
                   @NotNull(message="action can not be null") String action,
                   String resourceId,
                   String ownerId,
                   DataDomain dataDomain,
                   Map<String, Object> attributes) {
      Objects.requireNonNull(realm, "realm can not be null");
      Objects.requireNonNull(area, "area can not be null");
      Objects.requireNonNull(functionalDomain, "functionalDomain can not be null");
      Objects.requireNonNull(action, "action can not be null");


      this.realm = realm.toLowerCase();
      this.area = area.toLowerCase();
      this.functionalDomain = functionalDomain.toLowerCase();
      this.action = action.toLowerCase();
      this.resourceId = resourceId != null ? resourceId.toLowerCase() : null;
      this.ownerId = ownerId!= null ? ownerId.toLowerCase() : null;
      this.dataDomain = dataDomain;
      this.attributes = attributes == null || attributes.isEmpty()
              ? Collections.emptyMap()
              : Collections.unmodifiableMap(new HashMap<>(attributes));
   }

   public static class Builder {
      String any = "*";
      String realm = any;

      String area = any;
      String functionalDomain = any;
      String action = any;
      String resourceId = any;
      String ownerId = any;
      DataDomain dataDomain = null;
      Map<String, Object> attributes = new HashMap<>();

      public Builder withRealm(String realm) {
         this.realm = realm.toLowerCase();
         return this;
      }

      public Builder withArea(String area) {
         this.area = area.toLowerCase();
         return this;
      }

      public Builder withFunctionalDomain(String fd) {
         this.functionalDomain = fd.toLowerCase();
         return this;
      }

      public Builder withAction(String action) {
         this.action = action.toLowerCase();
         return this;
      }

      public Builder withResourceId(String id) {
         this.resourceId = id != null ? id.toLowerCase(): null;
         return this;
      }
       public Builder withOwnerId(String id) {
           this.ownerId = id != null ? id.toLowerCase(): null;
           return this;
       }

      public Builder withDataDomain(DataDomain dataDomain) {
         this.dataDomain = dataDomain;
         return this;
      }

      public Builder withAttributes(Map<String, Object> attributes) {
         if (attributes != null) {
            this.attributes = new HashMap<>(attributes);
         }
         return this;
      }

      public Builder withAttribute(String key, Object value) {
         if (key != null && !key.isBlank() && value != null) {
            this.attributes.put(key, value);
         }
         return this;
      }

      public ResourceContext build() {
         return new ResourceContext( realm, area,
            functionalDomain, action, resourceId, ownerId, dataDomain, attributes);
      }
   };

   @HostAccess.Export
   public String getRealm () {
      return realm;
   }
   public void setRealm (String realm) {
      this.realm = realm.toLowerCase();
   }

   @HostAccess.Export
   public String getArea () {
      return area;
   }
   public void setArea (String area) {
      this.area = area.toLowerCase();
   }

   @HostAccess.Export
   public String getFunctionalDomain () {
      return functionalDomain;
   }
   public void setFunctionalDomain (String functionalDomain) {
      this.functionalDomain = functionalDomain.toLowerCase();
   }

   @HostAccess.Export
   public String getAction () {
      return action;
   }
   public void setAction (String action) {
      this.action = action.toLowerCase();
   }

   @HostAccess.Export
   public @Nullable String getResourceId () {
      return resourceId;
   }
   public void setResourceId (String resourceId) {
      this.resourceId = resourceId.toLowerCase();
   }

   @HostAccess.Export
   public @Nullable String getOwnerId () {return ownerId;}
   public void setOwnerId (String ownerId) {this.ownerId = ownerId.toLowerCase();}

   @HostAccess.Export
   public @Nullable DataDomain getDataDomain() {
      return dataDomain;
   }

   public void setDataDomain(DataDomain dataDomain) {
      this.dataDomain = dataDomain;
   }

   @HostAccess.Export
   public Map<String, Object> getAttributes() {
      return attributes == null ? Collections.emptyMap() : attributes;
   }

   public void setAttributes(Map<String, Object> attributes) {
      this.attributes = attributes == null || attributes.isEmpty()
              ? Collections.emptyMap()
              : Collections.unmodifiableMap(new HashMap<>(attributes));
   }

   public ResourceContext withDataDomain(DataDomain dataDomain) {
      return new ResourceContext(realm, area, functionalDomain, action, resourceId, ownerId, dataDomain, attributes);
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
                ", ownerId='" + ownerId + '\'' +
                ", dataDomain='" + dataDomain + '\'' +
                ", attributes='" + attributes + '\'' +
                '}';
   }
}

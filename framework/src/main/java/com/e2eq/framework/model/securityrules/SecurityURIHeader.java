package com.e2eq.framework.model.securityrules;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;

@RegisterForReflection
public final class SecurityURIHeader {
  @NotNull String identity;
  @NotNull  String area;
  @NotNull  String functionalDomain;
  @NotNull  String action;

   public SecurityURIHeader() {
      String any = "*";
      identity = any;
      area = any;
      functionalDomain = any;
      action = any;
   }
   public SecurityURIHeader(@NotNull String identity, @NotNull String area, @NotNull String functionalDomain, @NotNull String action) {
      this.identity = identity.toLowerCase();
      this.area = area.toLowerCase();
      this.functionalDomain = functionalDomain.toLowerCase();
      this.action = action.toLowerCase();
   }

   public static class Builder {
      String identity;
      String area;
      String functionalDomain;
      String action;

      public Builder() {
      }

      public SecurityURIHeader.Builder withIdentity (String identity) {
         this.identity = identity.toLowerCase();
         return this;
      }

      public SecurityURIHeader.Builder withArea (@org.jetbrains.annotations.NotNull String area) {
         this.area = area.toLowerCase();
         return this;
      }

      public SecurityURIHeader.Builder withFunctionalDomain(String fd) {

         this.functionalDomain = fd.toLowerCase();
         return this;
      }

      public SecurityURIHeader.Builder withAction(String action) {
         this.action = action.toLowerCase();
         return this;
      }

      public SecurityURIHeader build() {
         return new SecurityURIHeader(identity, area, functionalDomain, action);
      }
   }

   public String getIdentity () {
      return identity;
   }

   public void setIdentity(@NotNull String identity ) {
      this.identity = identity;
   }

   public String getArea () {
      return area;
   }

   public void setArea(@NotNull String area) {
      this.area = area;
   }

   public String getFunctionalDomain () {
      return functionalDomain;
   }

   public void setFunctionalDomain(@NotNull String functionalDomain) {
      this.functionalDomain = functionalDomain;
   }

   public String getAction () {
      return action;
   }

   public void setAction(@NotNull String action) {
      this.action= action;
   }

   public SecurityURIHeader clone() {
      return new SecurityURIHeader.Builder()
                                       .withIdentity(identity)
                                       .withAction(action)
                                       .withArea(area)
                                       .withFunctionalDomain(functionalDomain)
                                       .build();
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof SecurityURIHeader)) return false;

      SecurityURIHeader that = (SecurityURIHeader) o;

      if (identity != null ? !identity.equals(that.identity) : that.identity != null) return false;
      if (area != null ? !area.equals(that.area) : that.area != null) return false;
      if (functionalDomain != null ? !functionalDomain.equals(that.functionalDomain) : that.functionalDomain != null)
         return false;
      return action != null ? action.equals(that.action) : that.action == null;
   }

   @Override
   public int hashCode () {
      int result = identity != null ? identity.hashCode() : 0;
      result = 31 * result + (area != null ? area.hashCode() : 0);
      result = 31 * result + (functionalDomain != null ? functionalDomain.hashCode() : 0);
      result = 31 * result + (action != null ? action.hashCode() : 0);
      return result;
   }

   public String toString(){
      return identity + ":" + area + ":" + functionalDomain + ":" + action;
   }
}

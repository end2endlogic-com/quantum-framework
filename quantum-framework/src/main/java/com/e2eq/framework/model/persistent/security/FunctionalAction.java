package com.e2eq.framework.model.persistent.security;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class FunctionalAction {
   String refName;
   String displayName;

   List<String> tags;

   public String getRefName () {
      return refName;
   }

   public void setRefName (String refName) {
      this.refName = refName;
   }

   public String getDisplayName () {
      return displayName;
   }

   public void setDisplayName (String displayName) {
      this.displayName = displayName;
   }

   public List<String> getTags () {
      return tags;
   }

   public void setTags (List<String> tags) {
      this.tags = tags;
   }

   @Override
   public boolean equals (Object o) {
      if (this == o) return true;
      if (!(o instanceof FunctionalAction)) return false;

      FunctionalAction that = (FunctionalAction) o;

      if (refName != null ? !refName.equals(that.refName) : that.refName != null) return false;
      if (displayName != null ? !displayName.equals(that.displayName) : that.displayName != null) return false;
      return tags != null ? tags.equals(that.tags) : that.tags == null;
   }

   @Override
   public int hashCode () {
      int result = refName != null ? refName.hashCode() : 0;
      result = 31 * result + (displayName != null ? displayName.hashCode() : 0);
      result = 31 * result + (tags != null ? tags.hashCode() : 0);
      return result;
   }

   @Override
   public String toString () {
      return refName;
   }
}
